package com.netcracker.it.storage.scenario;

/** The DBaaS PostgreSQL client against a Patroni leader change. */
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
