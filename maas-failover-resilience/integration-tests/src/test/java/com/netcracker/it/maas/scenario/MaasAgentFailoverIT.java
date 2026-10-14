package com.netcracker.it.maas.scenario;

import com.netcracker.cloud.junit.cloudcore.extension.annotations.EnableExtension;

/** The Java MaaS client while maas-agent loses an instance underneath it. */
@EnableExtension
class MaasAgentFailoverIT extends SpringFailoverITBase {

    @Override
    protected FailoverProfile profile() {
        return FailoverProfile.MAAS_AGENT;
    }
}
