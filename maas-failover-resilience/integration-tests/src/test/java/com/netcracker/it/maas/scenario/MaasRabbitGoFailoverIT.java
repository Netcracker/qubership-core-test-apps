package com.netcracker.it.maas.scenario;

import com.netcracker.cloud.junit.cloudcore.extension.annotations.EnableExtension;
import org.junit.jupiter.api.Disabled;

/**
 * The Go MaaS client, obtaining a vhost while the database behind maas-service moves its leader.
 *
 * <p>Disabled until a client carrying the fix is released: get-or-create never succeeds, whatever
 * the storage is doing. See "The Go client posts a bare classifier to the Rabbit vhost endpoint"
 * in README.md.
 */
@Disabled("needs a Go maas-client release carrying the vhost payload fix; see README.md")
@EnableExtension
class MaasRabbitGoFailoverIT extends GoFailoverITBase {

    @Override
    protected FailoverProfile profile() {
        return FailoverProfile.MAAS_RABBIT;
    }
}
