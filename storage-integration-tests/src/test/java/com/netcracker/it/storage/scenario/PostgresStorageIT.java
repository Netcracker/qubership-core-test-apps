package com.netcracker.it.storage.scenario;

import com.netcracker.cloud.junit.cloudcore.extension.annotations.EnableExtension;

/** The DBaaS PostgreSQL client against a Patroni leader change. */
@EnableExtension
class PostgresStorageIT extends StorageITBase {

    @Override
    protected String storage() {
        return "postgresql";
    }

    @Override
    protected Thresholds thresholds() {
        return Thresholds.postgresql();
    }
}
