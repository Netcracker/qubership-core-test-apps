package com.netcracker.it.storage.scenario;

import com.netcracker.cloud.junit.cloudcore.extension.annotations.EnableExtension;
import com.netcracker.it.storage.controller.FaultController;
import com.netcracker.it.storage.controller.KafkaFaultController;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Stream;

import static com.netcracker.it.storage.scenario.StorageAssertions.assertContract;

/**
 * Producing and consuming through a topic obtained from MaaS, across the loss of the broker.
 *
 * <p>The local-dev chart deploys one single-node KRaft broker per Helm release, so a partition
 * leader has nowhere to move: killing the pod takes the instance down and brings it back. That is
 * a weaker event than a leader election, and the scenario below says so in its name.
 */
@EnableExtension
class KafkaStorageIT extends StorageITBase {

    private static final String KAFKA_NAMESPACE = System.getProperty("storage.kafkaNamespace");
    private static final String KAFKA_INSTANCE = System.getProperty("storage.kafkaInstance");

    static Stream<Fault> faults() {
        return Stream.of(Fault.BROKER_LOSS);
    }

    @Override
    protected String storage() {
        return "kafka";
    }

    @Override
    protected Thresholds thresholds() {
        return Thresholds.kafka();
    }

    @Override
    protected Fault primaryFault() {
        return Fault.BROKER_LOSS;
    }

    /** Every operation waits for a broker acknowledgement, so ten per second would only queue. */
    @Override
    protected int operationsPerSecond() {
        return 5;
    }

    @Override
    protected FaultController newController() {
        return new KafkaFaultController(kubernetes, KAFKA_NAMESPACE, KAFKA_INSTANCE);
    }

    @Override
    protected List<String> requiredServices() {
        return List.of("maas-agent");
    }

    @Test
    @Disabled("the local-dev chart deploys one single-node broker per release, so a partition "
            + "leader cannot move between brokers. Enable once a multi-broker Kafka is available.")
    @DisplayName("the partition leader moves to another broker and the client follows")
    void partitionLeaderChange() {
        assertContract(runWorkloadThrough(Fault.GRACEFUL_SWITCHOVER, "PER_CALL"),
                faultClearedAt, thresholds(), 30);
    }
}
