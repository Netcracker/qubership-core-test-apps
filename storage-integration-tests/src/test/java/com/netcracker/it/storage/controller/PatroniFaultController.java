package com.netcracker.it.storage.controller;

import io.fabric8.kubernetes.api.model.Endpoints;
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
 * PostgreSQL under Patroni.
 *
 * <p>Leadership is read from the endpoints of the read-write Service: the operator points it at
 * whichever member is primary. That is more dependable than a pod label, which Patroni sets at
 * runtime and names differently across chart versions.
 */
public class PatroniFaultController implements FaultController {

    private static final Logger log = LoggerFactory.getLogger(PatroniFaultController.class);

    private static final Duration EXEC_TIMEOUT = Duration.ofSeconds(60);

    private final KubernetesClient client;
    private final String namespace;
    private final String leaderService;
    private final String memberPrefix;

    public PatroniFaultController(KubernetesClient client, String namespace,
                                  String leaderService, String memberPrefix) {
        this.client = client;
        this.namespace = namespace;
        this.leaderService = leaderService;
        this.memberPrefix = memberPrefix;
    }

    /** The pod currently behind the read-write Service. */
    private Optional<String> findLeader() {
        Endpoints endpoints = client.endpoints().inNamespace(namespace).withName(leaderService).get();
        if (endpoints == null || endpoints.getSubsets() == null) {
            return Optional.empty();
        }
        return endpoints.getSubsets().stream()
                .filter(subset -> subset.getAddresses() != null)
                .flatMap(subset -> subset.getAddresses().stream())
                .filter(address -> address.getTargetRef() != null)
                .map(address -> address.getTargetRef().getName())
                .findFirst();
    }

    private String leaderPod() {
        return findLeader().orElseThrow(() -> new IllegalStateException(
                "service " + namespace + "/" + leaderService + " has no ready endpoint, so there is no primary"));
    }

    /** Every member of the cluster; each Patroni node is its own StatefulSet, hence the prefix. */
    private List<Pod> members() {
        return client.pods().inNamespace(namespace).list().getItems().stream()
                .filter(pod -> pod.getMetadata().getName().startsWith(memberPrefix))
                .toList();
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
                        "switchover needs a second member, but " + leader + " is the only one."
                                + " Run with two Patroni replicas."));

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
        // an empty member list is a configuration error, not something to wait out
        if (members().isEmpty()) {
            throw new IllegalStateException("no pod in namespace " + namespace + " starts with '"
                    + memberPrefix + "'. Check the storage.memberPrefix property.");
        }
        await("a Patroni primary is serving and every member is ready")
                .atMost(timeout)
                .pollInterval(Duration.ofSeconds(2))
                .until(() -> findLeader().isPresent() && allMembersReady());
    }

    private boolean allMembersReady() {
        return members().stream().allMatch(this::isReady);
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
