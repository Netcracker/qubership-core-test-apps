package com.netcracker.it.storage.scenario;

import com.netcracker.cloud.junit.cloudcore.extension.annotations.EnableExtension;

/** The Go segmentio client producing and consuming through a topic obtained from MaaS. */
@EnableExtension
class KafkaGoStorageIT extends GoStorageITBase {

    @Override
    protected StorageProfile profile() {
        return StorageProfile.KAFKA;
    }
}
