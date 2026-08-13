package com.netcracker.it.storage.scenario;

import java.util.List;

/**
 * The MaaS client while the database behind maas-service moves its leader. A demoted leader
 * surfaces to the client as 405 from maas-service and 500 from maas-agent.
 */
class MaasStorageIT extends StorageITBase {

    @Override
    protected String storage() {
        return "maas-kafka";
    }

    @Override
    protected Thresholds thresholds() {
        return Thresholds.maas();
    }

    /** A get-or-create is a round trip through two services; ten per second would only queue. */
    @Override
    protected int operationsPerSecond() {
        return 2;
    }

    @Override
    protected List<String> requiredServices() {
        return List.of("maas-agent");
    }
}
