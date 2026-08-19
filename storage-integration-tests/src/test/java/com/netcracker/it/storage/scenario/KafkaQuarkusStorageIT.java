package com.netcracker.it.storage.scenario;

import com.netcracker.cloud.junit.cloudcore.extension.annotations.EnableExtension;

/** The Quarkus Kafka client producing and consuming through a topic obtained from MaaS. */
@EnableExtension
class KafkaQuarkusStorageIT extends QuarkusStorageITBase {

    @Override
    protected StorageProfile profile() {
        return StorageProfile.KAFKA;
    }
}
