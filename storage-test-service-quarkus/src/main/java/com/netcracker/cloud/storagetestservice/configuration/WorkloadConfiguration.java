package com.netcracker.cloud.storagetestservice.configuration;

import com.netcracker.cloud.maas.client.api.MaaSAPIClient;
import com.netcracker.cloud.storagetestservice.storage.KafkaProbe;
import com.netcracker.cloud.storagetestservice.storage.MaasKafkaProbe;
import com.netcracker.cloud.storagetestservice.storage.StorageProbe;
import com.netcracker.cloud.storagetestservice.workload.WorkloadRunner;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/** Wiring for the framework-free probes and runner in storage-test-service-common. */
@Dependent
public class WorkloadConfiguration {

    @Produces
    @Singleton
    public MaasKafkaProbe maasKafkaProbe(MaaSAPIClient maas) {
        return new MaasKafkaProbe(maas);
    }

    @Produces
    @Singleton
    public KafkaProbe kafkaProbe(MaaSAPIClient maas) {
        return new KafkaProbe(maas);
    }

    /** A produced bean gets no lifecycle callbacks, so the consumer thread is stopped here. */
    public void closeKafkaProbe(@Disposes KafkaProbe probe) {
        probe.close();
    }

    @Produces
    @Singleton
    public WorkloadRunner workloadRunner(Instance<StorageProbe> probes) {
        return new WorkloadRunner(probes.stream().toList());
    }
}
