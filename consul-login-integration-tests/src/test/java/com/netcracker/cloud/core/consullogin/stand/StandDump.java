package com.netcracker.cloud.core.consullogin.stand;

import com.fasterxml.jackson.databind.JsonNode;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.LifecycleMethodExecutionExceptionHandler;
import org.junit.jupiter.api.extension.TestWatcher;

import java.util.List;
import java.util.function.Supplier;

/**
 * Prints what the stand looked like when a scenario failed, so that the reason can be read off the run instead of
 * being guessed at and reproduced. Secrets stay out: tokens are listed by accessor, never by secret.
 *
 * <p>A scenario registers it over its own stand, and it prints on a failed test as well as on a failed preparation,
 * which is where most of what can break lives:
 *
 * <pre>{@code
 * @RegisterExtension
 * static final StandDump standDump = StandDump.onFailure(() -> consul, () -> kubernetes, NAMESPACE);
 * }</pre>
 *
 * <p>The stand is read through suppliers because a scenario builds it in its own preparation, after the extension is
 * registered.
 */
public final class StandDump implements TestWatcher, LifecycleMethodExecutionExceptionHandler {

    private final Supplier<ConsulClient> consul;
    private final Supplier<KubernetesClient> kubernetes;
    private final String namespace;

    private StandDump(Supplier<ConsulClient> consul, Supplier<KubernetesClient> kubernetes, String namespace) {
        this.consul = consul;
        this.kubernetes = kubernetes;
        this.namespace = namespace;
    }

    public static StandDump onFailure(Supplier<ConsulClient> consul, Supplier<KubernetesClient> kubernetes,
                                      String namespace) {
        return new StandDump(consul, kubernetes, namespace);
    }

    @Override
    public void testFailed(ExtensionContext context, Throwable cause) {
        print(context.getDisplayName());
    }

    @Override
    public void handleBeforeAllMethodExecutionException(ExtensionContext context, Throwable failure) throws Throwable {
        print("stand preparation");
        throw failure;
    }

    private void print(String title) {
        System.out.println();
        System.out.println("==== Consul login stand dump: " + title + " ====");
        printConsulState(consul.get());
        printPods(kubernetes.get(), namespace);
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
        printEvents(kubernetes, namespace);
        for (Pod pod : pods) {
            String name = pod.getMetadata().getName();
            System.out.println("-- pod " + namespace + "/" + name + " phase=" + pod.getStatus().getPhase());
            printContainerStatuses(pod);
            try {
                System.out.println(kubernetes.pods().inNamespace(namespace).withName(name).tailingLines(200).getLog());
            } catch (RuntimeException e) {
                System.out.println("   logs unavailable: " + e.getMessage());
            }
        }
    }

    private static void printContainerStatuses(Pod pod) {
        List<ContainerStatus> statuses = pod.getStatus().getContainerStatuses();
        if (statuses == null) {
            return;
        }
        for (ContainerStatus status : statuses) {
            if (status.getState() == null || status.getState().getWaiting() == null) {
                continue;
            }
            System.out.println("   container " + status.getName()
                    + " waiting: " + status.getState().getWaiting().getReason()
                    + " — " + status.getState().getWaiting().getMessage());
        }
    }

    private static void printEvents(KubernetesClient kubernetes, String namespace) {
        try {
            List<Event> events = kubernetes.v1().events().inNamespace(namespace).list().getItems();
            System.out.println("-- events in " + namespace + ":");
            for (Event event : events) {
                System.out.println("   " + event.getType() + " " + event.getReason() + " " + event.getMessage());
            }
        } catch (RuntimeException e) {
            System.out.println("-- events in " + namespace + ": unavailable, " + e.getMessage());
        }
    }

    private static void printList(String title, ConsulClient.Response response, Formatter formatter) {
        if (!response.isSuccessful()) {
            System.out.println("-- " + title + ": request failed with HTTP " + response.status());
            return;
        }
        JsonNode items;
        try {
            items = response.json();
        } catch (RuntimeException e) {
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
