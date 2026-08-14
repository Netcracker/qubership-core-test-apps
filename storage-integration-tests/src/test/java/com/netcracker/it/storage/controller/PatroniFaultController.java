package com.netcracker.it.storage.controller;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.ExecListener;
import io.fabric8.kubernetes.client.dsl.ExecWatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;

/**
 * PostgreSQL under Patroni. Leadership comes from the pod label Patroni maintains; handover uses
 * {@code patronictl}, which transfers leadership before the old leader stops serving.
 */
public class PatroniFaultController implements FaultController {

    private static final Logger log = LoggerFactory.getLogger(PatroniFaultController.class);

    /** Patroni labels its members with the current role; the leader carries {@code master}. */
    private static final String ROLE_LABEL = "role";
    private static final String LEADER_ROLE = "master";

    private static final Duration EXEC_TIMEOUT = Duration.ofSeconds(60);

    private final KubernetesClient client;
    private final String namespace;
    private final String clusterLabelKey;
    private final String clusterLabelValue;

    public PatroniFaultController(KubernetesClient client, String namespace,
                                  String clusterLabelKey, String clusterLabelValue) {
        this.client = client;
        this.namespace = namespace;
        this.clusterLabelKey = clusterLabelKey;
        this.clusterLabelValue = clusterLabelValue;
    }

    private String leaderPod() {
        return findLeader().orElseThrow(() -> new IllegalStateException(
                "no Patroni member is labelled " + ROLE_LABEL + "=" + LEADER_ROLE + " in " + namespace));
    }

    private Optional<String> findLeader() {
        return members().stream()
                .filter(pod -> LEADER_ROLE.equals(pod.getMetadata().getLabels().get(ROLE_LABEL)))
                .map(pod -> pod.getMetadata().getName())
                .findFirst();
    }

    private List<Pod> members() {
        return client.pods().inNamespace(namespace)
                .withLabel(clusterLabelKey, clusterLabelValue)
                .list().getItems();
    }

    @Override
    public void killLeader() {
        String leader = leaderPod();
        log.info("Killing Patroni leader {}", leader);
        // zero grace period on purpose: the abrupt scenario needs a reset, not a handover
        client.pods().inNamespace(namespace).withName(leader).withGracePeriod(0).delete();
    }

    @Override
    public void switchover() {
        String leader = leaderPod();
        String candidate = members().stream()
                .map(pod -> pod.getMetadata().getName())
                .filter(name -> !name.equals(leader))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "switchover needs a second member, but " + leader + " is the only one"));

        log.info("Handing leadership from {} to {}", leader, candidate);
        exec(leader, "patronictl", "switchover", "--leader", leader, "--candidate", candidate, "--force");
    }

    @Override
    public void rollingRestart() {
        for (Pod pod : members()) {
            String name = pod.getMetadata().getName();
            log.info("Rolling restart: deleting {}", name);
            client.pods().inNamespace(namespace).withName(name).withGracePeriod(0).delete();
            awaitStable(Duration.ofMinutes(3));
        }
    }

    @Override
    public void awaitStable(Duration timeout) {
        await("a Patroni leader is elected and every member is ready")
                .atMost(timeout)
                .pollInterval(Duration.ofSeconds(2))
                .until(() -> findLeader().isPresent() && allMembersReady());
    }

    private boolean allMembersReady() {
        List<Pod> members = members();
        return !members.isEmpty() && members.stream().allMatch(this::isReady);
    }

    private boolean isReady(Pod pod) {
        return pod.getStatus() != null && pod.getStatus().getConditions() != null
                && pod.getStatus().getConditions().stream()
                .anyMatch(c -> "Ready".equals(c.getType()) && "True".equals(c.getStatus()));
    }

    /** Runs a command in a member pod, the way an operator would. */
    private void exec(String pod, String... command) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        CompletableFuture<Integer> exited = new CompletableFuture<>();

        log.info("exec {}/{}: {}", namespace, pod, String.join(" ", command));
        try (ExecWatch ignored = client.pods().inNamespace(namespace).withName(pod)
                .writingOutput(out)
                .writingError(err)
                .usingListener(new ExecListener() {
                    @Override
                    public void onClose(int code, String reason) {
                        exited.complete(code);
                    }

                    @Override
                    public void onFailure(Throwable t, Response failureResponse) {
                        exited.completeExceptionally(t);
                    }
                })
                .exec(command)) {
            exited.get(EXEC_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while running " + String.join(" ", command), e);
        } catch (Exception e) {
            throw new IllegalStateException("failed to run " + String.join(" ", command) + " in "
                    + namespace + "/" + pod + ": " + err.toString(StandardCharsets.UTF_8), e);
        }
        log.debug("exec output: {}", out.toString(StandardCharsets.UTF_8));
    }
}
