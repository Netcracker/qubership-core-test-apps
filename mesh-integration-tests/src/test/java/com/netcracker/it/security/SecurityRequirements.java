package com.netcracker.it.security;

import io.fabric8.kubernetes.api.model.Capabilities;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.PodSecurityContext;
import io.fabric8.kubernetes.api.model.PodSpec;
import io.fabric8.kubernetes.api.model.SeccompProfile;
import io.fabric8.kubernetes.api.model.SecurityContext;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeMount;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Evaluates a {@link Workload} against the container security requirements and returns a list of
 * human-readable violations. An empty list means the workload complies.
 *
 * <p>Each message names the rule it breaks: non-root, no privilege escalation, no hostPID/hostIPC,
 * read-only root filesystem, drop all capabilities, RuntimeDefault seccomp, no shared mount
 * propagation, tagged image, no hostNetwork, no hostPath.
 */
public final class SecurityRequirements {

    private SecurityRequirements() {
    }

    /** Containers Istio injects into application pods; the application baseline does not own them. */
    public static final Set<String> ISTIO_INJECTED_CONTAINERS =
            Set.of("istio-proxy", "istio-init", "istio-validation", "enable-core-dump");

    /**
     * Full baseline. Every container (minus {@code skipContainers}) must run unprivileged and non-root,
     * drop all capabilities and add none, use a read-only root filesystem and the RuntimeDefault seccomp
     * profile, and pull a tagged image. The pod must not touch the host network, PID, IPC, or filesystem.
     */
    public static List<String> checkStrict(Workload workload, Set<String> skipContainers) {
        List<String> violations = new ArrayList<>();
        checkPodLevel(workload, violations, false);

        PodSpec pod = workload.podSpec();
        for (Container container : allContainers(pod)) {
            if (skipContainers.contains(container.getName())) {
                continue;
            }
            String where = workload.id() + "/" + container.getName();
            SecurityContext sc = container.getSecurityContext();

            checkImageTag(where, container, violations);

            if (!Boolean.FALSE.equals(value(sc, SecurityContext::getAllowPrivilegeEscalation))) {
                violations.add(where + ": allowPrivilegeEscalation must be false");
            }
            if (Boolean.TRUE.equals(value(sc, SecurityContext::getPrivileged))) {
                violations.add(where + ": privileged must not be true");
            }
            if (!Boolean.TRUE.equals(value(sc, SecurityContext::getReadOnlyRootFilesystem))) {
                violations.add(where + ": readOnlyRootFilesystem must be true");
            }
            if (!droppedCapabilities(sc).contains("ALL")) {
                violations.add(where + ": capabilities.drop must contain ALL");
            }
            List<String> added = addedCapabilities(sc);
            if (!added.isEmpty()) {
                violations.add(where + ": capabilities.add must be empty, found " + added);
            }
            if (!runsAsNonRoot(pod, container)) {
                violations.add(where + ": must run as non-root - set runAsNonRoot:true or runAsUser>=1000");
            }
            if (!"RuntimeDefault".equals(effectiveSeccompType(pod, container))) {
                violations.add(where + ": seccompProfile.type must be RuntimeDefault");
            }
            checkMountPropagation(where, container, violations);
        }
        return violations;
    }

    /**
     * Reduced baseline for components that require elevated privileges by design (the Istio CNI agent
     * and ztunnel). They may run as root, mount host paths, and add capabilities, but only from
     * {@code allowedAddedCapabilities}; they must never be fully privileged, must still drop all
     * capabilities first, and must not touch the host network, PID, or IPC. When {@code requireSeccomp}
     * is true the RuntimeDefault profile is still enforced.
     */
    public static List<String> checkDocumentedException(
            Workload workload, Set<String> allowedAddedCapabilities, boolean requireSeccomp) {
        List<String> violations = new ArrayList<>();
        checkPodLevel(workload, violations, true);

        PodSpec pod = workload.podSpec();
        for (Container container : allContainers(pod)) {
            String where = workload.id() + "/" + container.getName();
            SecurityContext sc = container.getSecurityContext();

            checkImageTag(where, container, violations);

            if (Boolean.TRUE.equals(value(sc, SecurityContext::getPrivileged))) {
                violations.add(where + ": privileged must not be true even for documented exceptions");
            }
            if (!droppedCapabilities(sc).contains("ALL")) {
                violations.add(where + ": capabilities.drop must contain ALL before adding any");
            }
            List<String> undocumented = addedCapabilities(sc).stream()
                    .filter(cap -> !allowedAddedCapabilities.contains(cap))
                    .collect(Collectors.toList());
            if (!undocumented.isEmpty()) {
                violations.add(where + ": undocumented added capabilities " + undocumented
                        + " - only " + allowedAddedCapabilities + " are allowed");
            }
            if (requireSeccomp && !"RuntimeDefault".equals(effectiveSeccompType(pod, container))) {
                violations.add(where + ": seccompProfile.type must be RuntimeDefault");
            }
        }
        return violations;
    }

    private static void checkPodLevel(Workload workload, List<String> violations, boolean allowHostPath) {
        PodSpec pod = workload.podSpec();
        String id = workload.id();

        if (Boolean.TRUE.equals(pod.getHostNetwork())) {
            violations.add(id + ": hostNetwork must not be true");
        }
        if (Boolean.TRUE.equals(pod.getHostPID())) {
            violations.add(id + ": hostPID must not be true");
        }
        if (Boolean.TRUE.equals(pod.getHostIPC())) {
            violations.add(id + ": hostIPC must not be true");
        }
        if (!allowHostPath && pod.getVolumes() != null) {
            List<String> hostPaths = pod.getVolumes().stream()
                    .filter(volume -> volume.getHostPath() != null)
                    .map(Volume::getName)
                    .collect(Collectors.toList());
            if (!hostPaths.isEmpty()) {
                violations.add(id + ": hostPath volumes are not allowed " + hostPaths);
            }
        }
    }

    private static void checkImageTag(String where, Container container, List<String> violations) {
        String image = container.getImage();
        if (image == null || image.isBlank()) {
            violations.add(where + ": container has no image");
            return;
        }
        String reference = image.contains("/") ? image.substring(image.lastIndexOf('/') + 1) : image;
        boolean tagged = reference.contains(":") || image.contains("@");
        if (!tagged) {
            violations.add(where + ": image '" + image + "' must use an explicit tag");
        }
    }

    private static void checkMountPropagation(String where, Container container, List<String> violations) {
        if (container.getVolumeMounts() == null) {
            return;
        }
        for (VolumeMount mount : container.getVolumeMounts()) {
            if ("Bidirectional".equals(mount.getMountPropagation())) {
                violations.add(where + ": mountPropagation for '" + mount.getName()
                        + "' must not be Bidirectional");
            }
        }
    }

    private static boolean runsAsNonRoot(PodSpec pod, Container container) {
        Long uid = firstNonNull(
                value(container.getSecurityContext(), SecurityContext::getRunAsUser),
                value(pod.getSecurityContext(), PodSecurityContext::getRunAsUser));
        if (uid != null) {
            return uid >= 1000;
        }
        Boolean nonRoot = firstNonNull(
                value(container.getSecurityContext(), SecurityContext::getRunAsNonRoot),
                value(pod.getSecurityContext(), PodSecurityContext::getRunAsNonRoot));
        return Boolean.TRUE.equals(nonRoot);
    }

    private static String effectiveSeccompType(PodSpec pod, Container container) {
        SeccompProfile fromContainer = value(container.getSecurityContext(), SecurityContext::getSeccompProfile);
        if (fromContainer != null) {
            return fromContainer.getType();
        }
        SeccompProfile fromPod = value(pod.getSecurityContext(), PodSecurityContext::getSeccompProfile);
        return fromPod == null ? null : fromPod.getType();
    }

    private static List<String> droppedCapabilities(SecurityContext sc) {
        Capabilities capabilities = value(sc, SecurityContext::getCapabilities);
        if (capabilities == null || capabilities.getDrop() == null) {
            return Collections.emptyList();
        }
        return capabilities.getDrop();
    }

    private static List<String> addedCapabilities(SecurityContext sc) {
        Capabilities capabilities = value(sc, SecurityContext::getCapabilities);
        if (capabilities == null || capabilities.getAdd() == null) {
            return Collections.emptyList();
        }
        return capabilities.getAdd();
    }

    private static List<Container> allContainers(PodSpec pod) {
        Stream<Container> init = pod.getInitContainers() == null
                ? Stream.empty() : pod.getInitContainers().stream();
        Stream<Container> main = pod.getContainers() == null
                ? Stream.empty() : pod.getContainers().stream();
        return Stream.concat(init, main).collect(Collectors.toList());
    }

    private static <T, R> R value(T source, java.util.function.Function<T, R> getter) {
        return source == null ? null : getter.apply(source);
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }
}
