package com.netcracker.it.storage.scenario;

import com.netcracker.cloud.junit.cloudcore.extension.annotations.EnableExtension;

/** The Java MaaS client, the watch subscription while the database behind maas-service moves its leader. */
@EnableExtension
class MaasWatchStorageIT extends SpringStorageITBase {

    @Override
    protected StorageProfile profile() {
        return StorageProfile.MAAS_WATCH;
    }
}
