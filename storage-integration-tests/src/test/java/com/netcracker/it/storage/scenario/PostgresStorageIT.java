package com.netcracker.it.storage.scenario;

import com.netcracker.cloud.junit.cloudcore.extension.annotations.EnableExtension;

/** The Java DBaaS PostgreSQL client against a Patroni leader change. */
@EnableExtension
class PostgresStorageIT extends SpringStorageITBase {

    @Override
    protected StorageProfile profile() {
        return StorageProfile.POSTGRESQL;
    }
}
