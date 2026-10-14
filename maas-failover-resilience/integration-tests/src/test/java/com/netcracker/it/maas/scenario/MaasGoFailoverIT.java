package com.netcracker.it.maas.scenario;

import com.netcracker.cloud.junit.cloudcore.extension.annotations.EnableExtension;

/** The Go MaaS client while the database behind maas-service moves its leader. */
@EnableExtension
class MaasGoFailoverIT extends GoFailoverITBase {

    @Override
    protected FailoverProfile profile() {
        return FailoverProfile.MAAS_KAFKA;
    }
}
