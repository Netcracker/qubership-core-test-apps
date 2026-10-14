package com.netcracker.it.maas.scenario;

import com.netcracker.cloud.junit.cloudcore.extension.annotations.EnableExtension;

/** The Go MaaS client, the watch subscription while the database behind maas-service moves its leader. */
@EnableExtension
class MaasWatchGoFailoverIT extends GoFailoverITBase {

    @Override
    protected FailoverProfile profile() {
        return FailoverProfile.MAAS_WATCH;
    }
}
