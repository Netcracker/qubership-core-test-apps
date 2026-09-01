package com.netcracker.it.storage.scenario;

import com.netcracker.cloud.junit.cloudcore.extension.annotations.EnableExtension;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.netcracker.it.storage.scenario.StorageAssertions.assertContract;

/** The Java Kafka client producing and consuming through a topic obtained from MaaS. */
@EnableExtension
class KafkaStorageIT extends SpringStorageITBase {

    @Override
    protected StorageProfile profile() {
        return StorageProfile.KAFKA;
    }

    @Test
    @Disabled("the local-dev chart deploys one single-node broker per release, so a partition "
            + "leader cannot move between brokers. Enable once a multi-broker Kafka is available.")
    @DisplayName("the partition leader moves to another broker and the client follows")
    void partitionLeaderChange() {
        assertContract(runWorkloadThrough(Fault.GRACEFUL_SWITCHOVER, "PER_CALL"),
                faultClearedAt, profile().thresholds(), 30);
    }
}
