package com.netcracker.it.storage.scenario;

import com.netcracker.cloud.junit.cloudcore.extension.annotations.EnableExtension;

/** The Quarkus MaaS client while the database behind maas-service moves its leader. */
@EnableExtension
class MaasQuarkusStorageIT extends QuarkusStorageITBase {

    @Override
    protected StorageProfile profile() {
        return StorageProfile.MAAS_KAFKA;
    }
}
