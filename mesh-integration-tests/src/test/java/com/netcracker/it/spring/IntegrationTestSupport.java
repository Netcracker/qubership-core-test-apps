package com.netcracker.it.spring;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.ExecWatch;
import lombok.extern.slf4j.Slf4j;
import org.awaitility.core.ConditionTimeoutException;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.time.Duration;

import static org.awaitility.Awaitility.await;

@Slf4j
public final class IntegrationTestSupport {

    public static final long POLL_INTERVAL_MS = 2_000;
    public static final int STATUS_UNKNOWN = -1;

    private static KubernetesClient kubernetesClient;
    private static String namespace;
    // Shared executor pod for current test JVM; this helper is not thread-safe for parallel IT execution.
    private static String executorPod;

    private IntegrationTestSupport() {
    }

    public static void init(KubernetesClient client) {
        kubernetesClient = client;
        namespace = client.getNamespace();
        executorPod = resolveExecutorPod();
    }

    public static boolean isExecutorAvailable() {
        return executorPod != null;
    }

    public static void waitUntilHttpOk(String label, long timeoutMs, HttpStatusProbe... statusProbes) throws Exception {
        int[] lastStatuses = new int[statusProbes.length];
        Arrays.fill(lastStatuses, STATUS_UNKNOWN);
        try {
            await()
                    .atMost(Duration.ofMillis(timeoutMs))
                    .pollInterval(Duration.ofMillis(POLL_INTERVAL_MS))
                    .until(() -> {
                        for (int i = 0; i < statusProbes.length; i++) {
                            lastStatuses[i] = statusProbes[i].getStatus();
                            if (lastStatuses[i] == 200) {
                                log.info("{} ready (http={})", label, lastStatuses[i]);
                                return true;
                            }
                        }
                        return false;
                    });
        } catch (ConditionTimeoutException e) {
            throw new IllegalStateException(label + " not ready (last http=" + Arrays.toString(lastStatuses) + ")", e);
        }
    }

    @FunctionalInterface
    public interface HttpStatusProbe {
        int getStatus() throws Exception;
    }

    public static String inClusterServiceUrl(String serviceName, String path) {
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        return String.format("http://%s.%s.svc.cluster.local:8080%s", serviceName, namespace, path);
    }

    public static String executeInClusterPod(String... command) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        log.debug("Executing in pod {}: {}", executorPod, String.join(" ", command));

        try (ExecWatch execWatch = kubernetesClient.pods()
                .inNamespace(namespace)
                .withName(executorPod)
                .writingOutput(out)
                .writingError(err)
                .exec(command)) {

            Integer exitCode = execWatch.exitCode().get();
            String output = out.toString(StandardCharsets.UTF_8);
            if (exitCode != null && exitCode != 0) {
                log.warn("Command {} exited {}: {}", String.join(" ", command), exitCode, err);
            }
            return output;
        }
    }

    public static int getHttpStatus(String url) throws Exception {
        String result = executeInClusterPod("curl", "-s", "-o", "/dev/null", "-w", "%{http_code}", url);
        if (result == null || result.trim().isEmpty()) {
            return STATUS_UNKNOWN;
        }
        try {
            return Integer.parseInt(result.trim());
        } catch (NumberFormatException e) {
            log.error("Failed to parse curl status for {}: '{}'", url, result);
            return STATUS_UNKNOWN;
        }
    }

    private static String resolveExecutorPod() {
        var pods = kubernetesClient.pods()
                .inNamespace(namespace)
                .withLabel("name", Const.MESH_TEST_SERVICE_SPRING_V1)
                .list();
        if (pods.getItems().isEmpty()) {
            return null;
        }
        return pods.getItems().getFirst().getMetadata().getName();
    }
}
