package com.netcracker.it.security;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Cluster access and configuration for the security tests.
 *
 * <p>The client auto-configures from the current kubeconfig context and honors the same
 * {@code kubernetes.master} system property the integration-test runner sets, so the tests reach the
 * same cluster as the rest of the suite.
 */
public final class KubeSupport {

    private KubeSupport() {
    }

    public static KubernetesClient newClient() {
        return new KubernetesClientBuilder().build();
    }

    /**
     * Namespace where the core microservices run. Override with {@code -Dsecurity.test.namespace=<ns>};
     * otherwise fall back to the properties the integration-test runner already passes.
     */
    public static String coreNamespace() {
        return firstNonBlank(
                System.getProperty("security.test.namespace"),
                System.getProperty("ORIGIN_NAMESPACE"),
                System.getProperty("env.cloud-namespace"),
                System.getProperty("clouds.cloud.namespaces.namespace"),
                "core");
    }

    /** Namespace where Istio runs. Override with {@code -Dsecurity.test.istio.namespace=<ns>}. */
    public static String istioNamespace() {
        return firstNonBlank(
                System.getProperty("security.test.istio.namespace"),
                System.getProperty("ISTIO_NAMESPACE"),
                "istio-system");
    }

    /** Workload names to skip, as a comma-separated {@code -Dsecurity.test.exclude=a,b} list. */
    public static Set<String> excludedWorkloads() {
        String raw = System.getProperty("security.test.exclude", "");
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    /** Every Deployment, StatefulSet, and DaemonSet in the namespace, as {@link Workload} records. */
    public static List<Workload> workloads(KubernetesClient client, String namespace) {
        List<Workload> out = new ArrayList<>();
        client.apps().deployments().inNamespace(namespace).list().getItems().forEach(d ->
                out.add(new Workload("Deployment", d.getMetadata().getName(), namespace, d.getSpec().getTemplate())));
        client.apps().statefulSets().inNamespace(namespace).list().getItems().forEach(s ->
                out.add(new Workload("StatefulSet", s.getMetadata().getName(), namespace, s.getSpec().getTemplate())));
        client.apps().daemonSets().inNamespace(namespace).list().getItems().forEach(ds ->
                out.add(new Workload("DaemonSet", ds.getMetadata().getName(), namespace, ds.getSpec().getTemplate())));
        return out;
    }

    static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
