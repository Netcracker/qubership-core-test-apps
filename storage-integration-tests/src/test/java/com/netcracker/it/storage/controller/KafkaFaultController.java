package com.netcracker.it.storage.controller;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;

import static org.awaitility.Awaitility.await;

/**
 * Kafka as the local-dev install deploys it: one broker per Helm release, each its own KRaft
 * quorum. Killing the broker takes the whole instance down and brings it back, which is a
 * different event from moving a partition leader between brokers.
 */
public class KafkaFaultController implements FaultController {

    private static final Logger log = LoggerFactory.getLogger(KafkaFaultController.class);

    private final KubernetesClient client;
    private final String namespace;
    private final String instance;

    public KafkaFaultController(KubernetesClient client, String namespace, String instance) {
        this.client = client;
        this.namespace = namespace;
        this.instance = instance;
    }

    private List<Pod> brokers() {
        return client.pods().inNamespace(namespace).withLabel("app", instance).list().getItems();
    }

    @Override
    public void killLeader() {
        brokers().forEach(pod -> {
            log.info("Killing Kafka broker {}", pod.getMetadata().getName());
            client.pods().inNamespace(namespace).withName(pod.getMetadata().getName())
                    .withGracePeriod(0).delete();
        });
    }

    @Override
    public void switchover() {
        throw new UnsupportedOperationException(
                "moving a partition leader needs several brokers in one cluster; the local-dev chart"
                        + " deploys a single-node KRaft instance per release");
    }

    @Override
    public void rollingRestart() {
        killLeader();
    }

    @Override
    public void awaitStable(Duration timeout) {
        if (brokers().isEmpty()) {
            throw new IllegalStateException("no pod labelled app=" + instance + " in namespace "
                    + namespace + ". Check the storage.kafkaInstance property.");
        }
        await("the Kafka broker is ready")
                .atMost(timeout)
                .pollInterval(Duration.ofSeconds(2))
                .until(() -> !brokers().isEmpty() && brokers().stream().allMatch(this::isReady));
    }

    private boolean isReady(Pod pod) {
        return pod.getStatus() != null && pod.getStatus().getConditions() != null
                && pod.getStatus().getConditions().stream()
                .anyMatch(c -> "Ready".equals(c.getType()) && "True".equals(c.getStatus()));
    }
}
