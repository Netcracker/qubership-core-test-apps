package com.netcracker.it.storage.scenario;

import com.netcracker.cloud.junit.cloudcore.extension.annotations.EnableExtension;

/** The Go MaaS client while maas-agent loses an instance underneath it. */
@EnableExtension
class MaasAgentGoStorageIT extends GoStorageITBase {

    @Override
    protected StorageProfile profile() {
        return StorageProfile.MAAS_AGENT;
    }
}
