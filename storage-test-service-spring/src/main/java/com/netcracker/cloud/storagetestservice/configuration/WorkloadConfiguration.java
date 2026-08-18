package com.netcracker.cloud.storagetestservice.configuration;

import com.netcracker.cloud.maas.client.api.MaaSAPIClient;
import com.netcracker.cloud.storagetestservice.storage.KafkaProbe;
import com.netcracker.cloud.storagetestservice.storage.MaasKafkaProbe;
import com.netcracker.cloud.storagetestservice.storage.MaasRabbitProbe;
import com.netcracker.cloud.storagetestservice.storage.MaasWatchProbe;
import com.netcracker.cloud.storagetestservice.storage.StorageProbe;
import com.netcracker.cloud.storagetestservice.workload.WorkloadRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/** Wiring for the framework-free probes and runner in storage-test-service-common. */
@Configuration
public class WorkloadConfiguration {

    @Bean
    public MaasKafkaProbe maasKafkaProbe(MaaSAPIClient maas) {
        return new MaasKafkaProbe(maas);
    }

    // close() is inferred: the probe is AutoCloseable
    @Bean
    public KafkaProbe kafkaProbe(MaaSAPIClient maas) {
        return new KafkaProbe(maas);
    }

    @Bean
    public MaasRabbitProbe maasRabbitProbe(MaaSAPIClient maas) {
        return new MaasRabbitProbe(maas);
    }

    @Bean
    public MaasWatchProbe maasWatchProbe(MaaSAPIClient maas) {
        return new MaasWatchProbe(maas);
    }

    @Bean
    public WorkloadRunner workloadRunner(List<StorageProbe> probes) {
        return new WorkloadRunner(probes);
    }
}
