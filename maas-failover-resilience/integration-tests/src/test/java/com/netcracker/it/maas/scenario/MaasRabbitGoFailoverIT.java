package com.netcracker.it.maas.scenario;

import com.netcracker.cloud.junit.cloudcore.extension.annotations.EnableExtension;

/**
 * The Go MaaS client, obtaining a vhost while the database behind maas-service moves its leader.
 */
@EnableExtension
class MaasRabbitGoFailoverIT extends GoFailoverITBase {

    @Override
    protected FailoverProfile profile() {
        return FailoverProfile.MAAS_RABBIT;
    }
}
