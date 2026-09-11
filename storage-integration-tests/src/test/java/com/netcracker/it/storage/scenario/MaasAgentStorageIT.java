package com.netcracker.it.storage.scenario;

import com.netcracker.cloud.junit.cloudcore.extension.annotations.EnableExtension;

/** The Java MaaS client while maas-agent loses an instance underneath it. */
@EnableExtension
class MaasAgentStorageIT extends SpringStorageITBase {

    @Override
    protected StorageProfile profile() {
        return StorageProfile.MAAS_AGENT;
    }
}
