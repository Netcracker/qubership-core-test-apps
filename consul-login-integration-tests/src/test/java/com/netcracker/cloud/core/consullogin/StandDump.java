package com.netcracker.cloud.core.consullogin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;

import java.util.List;

/**
 * Prints what the stand looked like when a test failed, so that the reason can be read off the run instead of
 * being guessed at and reproduced. Secrets stay out: tokens are listed by accessor, never by secret.
 */
final class StandDump {

    private static final ObjectMapper JSON = new ObjectMapper();

    private StandDump() {
    }

    static void print(String title, ConsulClient consul, KubernetesClient kubernetes, String namespace) {
        System.out.println();
        System.out.println("==== Consul login stand dump: " + title + " ====");
        printConsulState(consul);
        printPods(kubernetes, namespace);
        System.out.println("==== end of dump ====");
        System.out.println();
    }

    private static void printConsulState(ConsulClient consul) {
        if (consul == null) {
            System.out.println("Consul client was not built yet");
            return;
        }
        printList("auth methods", consul.get("/v1/acl/auth-methods"), node -> node.path("Name").asText()
                + " type=" + node.path("Type").asText());
        printList("binding rules", consul.get("/v1/acl/binding-rules"), node -> node.path("ID").asText()
                + " authMethod=" + node.path("AuthMethod").asText()
                + " selector=" + node.path("Selector").asText()
                + " bind=" + node.path("BindType").asText() + ":" + node.path("BindName").asText());
        printList("tokens issued by an auth method", consul.get("/v1/acl/tokens"), node -> node.path("AccessorID").asText()
                + " authMethod=" + node.path("AuthMethod").asText()
                + " roles=" + names(node.path("Roles"))
                + " policies=" + names(node.path("Policies")));
    }

    private static void printPods(KubernetesClient kubernetes, String namespace) {
        if (kubernetes == null || namespace == null) {
            return;
        }
        List<Pod> pods;
        try {
            pods = kubernetes.pods().inNamespace(namespace).list().getItems();
        } catch (RuntimeException e) {
            System.out.println("-- pods in " + namespace + ": unavailable, " + e.getMessage());
            return;
        }
        for (Pod pod : pods) {
            String name = pod.getMetadata().getName();
            System.out.println("-- pod " + namespace + "/" + name + " phase=" + pod.getStatus().getPhase());
            try {
                System.out.println(kubernetes.pods().inNamespace(namespace).withName(name).tailingLines(200).getLog());
            } catch (RuntimeException e) {
                System.out.println("   logs unavailable: " + e.getMessage());
            }
        }
    }

    private static void printList(String title, ConsulClient.Response response, Formatter formatter) {
        if (!response.isSuccessful()) {
            System.out.println("-- " + title + ": request failed with HTTP " + response.status());
            return;
        }
        JsonNode items;
        try {
            items = JSON.readTree(response.body());
        } catch (Exception e) {
            System.out.println("-- " + title + ": unexpected response body");
            return;
        }
        System.out.println("-- " + title + ":");
        for (JsonNode item : items) {
            if (isFromAuthMethod(title, item)) {
                System.out.println("   " + formatter.format(item));
            }
        }
    }

    private static boolean isFromAuthMethod(String title, JsonNode item) {
        return !title.startsWith("tokens") || !item.path("AuthMethod").asText().isEmpty();
    }

    private static String names(JsonNode nodes) {
        StringBuilder joined = new StringBuilder();
        for (JsonNode node : nodes) {
            joined.append(joined.isEmpty() ? "" : ",").append(node.path("Name").asText());
        }
        return joined.isEmpty() ? "-" : joined.toString();
    }

    @FunctionalInterface
    private interface Formatter {
        String format(JsonNode node);
    }
}
