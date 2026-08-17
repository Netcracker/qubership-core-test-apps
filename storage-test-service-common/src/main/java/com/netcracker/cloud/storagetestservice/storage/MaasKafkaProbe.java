package com.netcracker.cloud.storagetestservice.storage;

import com.netcracker.cloud.storagetestservice.workload.HandleMode;
import com.netcracker.cloud.maas.client.api.Classifier;
import com.netcracker.cloud.maas.client.api.MaaSAPIClient;
import com.netcracker.cloud.maas.client.api.kafka.KafkaMaaSClient;
import com.netcracker.cloud.maas.client.api.kafka.TopicAddress;
import com.netcracker.cloud.maas.client.api.kafka.TopicCreateOptions;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * MaaS control plane, not the broker. maas-service keeps its state in PostgreSQL, so a leader
 * change there surfaces as 405 from maas-service and 500 from maas-agent.
 */
public class MaasKafkaProbe implements StorageProbe {

    private final MaaSAPIClient maas;

    /** Resolved once at startup for LONG_HELD, the way a service that wires a client at boot holds it. */
    private volatile KafkaMaaSClient heldClient;

    public MaasKafkaProbe(MaaSAPIClient maas) {
        this.maas = maas;
    }

    @Override
    public String type() {
        return "maas-kafka";
    }

    @Override
    public void init() {
        kafkaClient(HandleMode.PER_CALL).getOrCreateTopic(classifier("probe"), TopicCreateOptions.DEFAULTS);
    }

    /**
     * One operation is a get-or-create, which is idempotent and goes through maas-agent to
     * maas-service to its database — the whole path a Postgres failover disturbs.
     */
    @Override
    public String writeAndRead(HandleMode handleMode, String key, String value) {
        TopicAddress address = kafkaClient(handleMode)
                .getOrCreateTopic(classifier(key), TopicCreateOptions.DEFAULTS);
        return address == null ? null : value;
    }

    @Override
    public String read(HandleMode handleMode, String key) {
        Optional<TopicAddress> topic = kafkaClient(handleMode).getTopic(classifier(key));
        return topic.map(TopicAddress::getTopicName).orElse(null);
    }

    @Override
    public void releaseHeldHandle() {
        heldClient = null;
    }

    @Override
    public Map<String, Object> diagnostics() {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("holdsClient", heldClient != null);
        return diagnostics;
    }

    private KafkaMaaSClient kafkaClient(HandleMode handleMode) {
        if (handleMode == HandleMode.PER_CALL) {
            return maas.getKafkaClient();
        }
        if (heldClient == null) {
            synchronized (this) {
                if (heldClient == null) {
                    heldClient = maas.getKafkaClient();
                }
            }
        }
        return heldClient;
    }

    /** A stable name per key, so repeated operations hit the same topic rather than creating many. */
    private static Classifier classifier(String key) {
        return new Classifier("storage-probe-" + key);
    }
}
