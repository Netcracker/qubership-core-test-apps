package com.netcracker.it.storage.scenario;

import com.netcracker.cloud.junit.cloudcore.extension.annotations.EnableExtension;

/** The Java MaaS client, obtaining a vhost while the database behind maas-service moves its leader. */
@EnableExtension
class MaasRabbitStorageIT extends SpringStorageITBase {

    @Override
    protected StorageProfile profile() {
        return StorageProfile.MAAS_RABBIT;
    }
}
