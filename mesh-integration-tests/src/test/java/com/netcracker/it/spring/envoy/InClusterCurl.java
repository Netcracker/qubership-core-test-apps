package com.netcracker.it.spring.envoy;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.ExecWatch;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Runs curl inside a mesh-test-service-spring pod: the request starts from a mesh workload (on Istio it passes
 * the waypoint) and the test controls every request header, which the spring proxy does not forward.
 */
class InClusterCurl {

    private static final String CLIENT_POD_NAME_LABEL = "mesh-test-service-spring-v1";

    private final KubernetesClient client;
    private final String namespace;
    private final String pod;

    InClusterCurl(KubernetesClient client) {
        this.client = client;
        this.namespace = client.getNamespace();
        this.pod = client.pods().inNamespace(namespace).withLabel("name", CLIENT_POD_NAME_LABEL).list().getItems()
                .stream()
                .findFirst()
                .map(p -> p.getMetadata().getName())
                .orElseThrow(() -> new IllegalStateException(
                        "No pod labeled name=%s in %s".formatted(CLIENT_POD_NAME_LABEL, namespace)));
    }

    /** GETs {@code http://hostAndPath} and returns curl's output with the response headers ({@code -i}). */
    String get(String hostAndPath, Map<String, String> headers) throws Exception {
        List<String> command = new ArrayList<>(List.of("curl", "-s", "-i"));
        headers.forEach((name, value) -> command.addAll(List.of("-H", name + ": " + value)));
        command.add("http://" + hostAndPath);
        return exec(command.toArray(String[]::new));
    }

    private String exec(String... command) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try (ExecWatch watch = client.pods()
                .inNamespace(namespace)
                .withName(pod)
                .writingOutput(out)
                .writingError(err)
                .exec(command)) {
            Integer exitCode = watch.exitCode().get();
            if (exitCode != null && exitCode != 0) {
                throw new IllegalStateException("'%s' in pod %s exited with %d: %s"
                        .formatted(String.join(" ", command), pod, exitCode, err.toString(StandardCharsets.UTF_8)));
            }
            return out.toString(StandardCharsets.UTF_8);
        }
    }
}
