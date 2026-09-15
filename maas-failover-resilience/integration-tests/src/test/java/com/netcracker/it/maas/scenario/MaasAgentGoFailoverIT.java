package com.netcracker.it.maas.scenario;

import com.netcracker.cloud.junit.cloudcore.extension.annotations.EnableExtension;

/** The Go MaaS client while maas-agent loses an instance underneath it. */
@EnableExtension
class MaasAgentGoFailoverIT extends GoFailoverITBase {

    @Override
    protected FailoverProfile profile() {
        return FailoverProfile.MAAS_AGENT;
    }
}
