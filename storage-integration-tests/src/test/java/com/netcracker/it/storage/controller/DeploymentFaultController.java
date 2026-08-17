package com.netcracker.it.storage.controller;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.awaitility.Awaitility.await;

/**
 * A stateless service behind a Service, such as maas-agent. There is no leader here: what the
 * client sees is one endpoint disappearing mid-request, which is a transport error rather than the
 * 405 a demoted database produces.
 */
public class DeploymentFaultController implements FaultController {

    private static final Logger log = LoggerFactory.getLogger(DeploymentFaultController.class);

    private static final String RESTART_ANNOTATION = "kubectl.kubernetes.io/restartedAt";

    private final KubernetesClient client;
    private final String namespace;
    private final String name;
    private final int replicas;

    public DeploymentFaultController(KubernetesClient client, String namespace, String name, int replicas) {
        this.client = client;
        this.namespace = namespace;
        this.name = name;
        this.replicas = replicas;
    }

    /** Deletes one instance with no grace period, so its open connections are reset. */
    @Override
    public void killLeader() {
        List<Pod> pods = pods();
        if (pods.isEmpty()) {
            throw new IllegalStateException("no pods of deployment " + name + " in namespace " + namespace);
        }
        String victim = pods.get(0).getMetadata().getName();
        log.info("Killing {} instance {}", name, victim);
        client.pods().inNamespace(namespace).withName(victim).withGracePeriod(0).delete();
    }

    @Override
    public void switchover() {
        throw new UnsupportedOperationException(
                name + " is stateless and has no leadership to hand over");
    }

    @Override
    public void rollingRestart() {
        log.info("Rolling restart of {}", name);
        client.apps().deployments().inNamespace(namespace).withName(name)
                .edit(deployment -> {
                    deployment.getSpec().getTemplate().getMetadata()
                            .setAnnotations(Map.of(RESTART_ANNOTATION, Instant.now().toString()));
                    return deployment;
                });
    }

    /**
     * Brings the deployment to the replica count the scenario needs and waits for all of them.
     * The local-dev profile installs a single instance, and losing one of one is a full outage
     * rather than the endpoint change being measured.
     */
    @Override
    public void awaitStable(Duration timeout) {
        Deployment deployment = deployment();
        if (deployment == null) {
            throw new IllegalStateException("deployment " + name + " is not present in namespace "
                    + namespace + ". Check the storage.maasAgentDeployment property.");
        }
        if (!Integer.valueOf(replicas).equals(deployment.getSpec().getReplicas())) {
            log.info("Scaling {} from {} to {} instances", name, deployment.getSpec().getReplicas(), replicas);
            client.apps().deployments().inNamespace(namespace).withName(name).scale(replicas);
        }
        await("all " + replicas + " instances of " + name + " are ready")
                .atMost(timeout)
                .pollInterval(Duration.ofSeconds(2))
                .until(this::allReady);
    }

    private boolean allReady() {
        Deployment deployment = deployment();
        return deployment != null && deployment.getStatus() != null
                && Integer.valueOf(replicas).equals(deployment.getStatus().getReadyReplicas());
    }

    private Deployment deployment() {
        return client.apps().deployments().inNamespace(namespace).withName(name).get();
    }

    private List<Pod> pods() {
        Map<String, String> selector = deployment().getSpec().getSelector().getMatchLabels();
        return client.pods().inNamespace(namespace).withLabels(selector).list().getItems();
    }
}
