package com.netcracker.it.maas.scenario;

import com.netcracker.cloud.junit.cloudcore.extension.annotations.EnableExtension;

/** The Java MaaS client while the database behind maas-service moves its leader. */
@EnableExtension
class MaasFailoverIT extends SpringFailoverITBase {

    @Override
    protected FailoverProfile profile() {
        return FailoverProfile.MAAS_KAFKA;
    }
}
