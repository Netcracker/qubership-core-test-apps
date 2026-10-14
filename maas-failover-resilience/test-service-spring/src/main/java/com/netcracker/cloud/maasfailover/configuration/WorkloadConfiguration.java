package com.netcracker.cloud.maasfailover.configuration;

import com.netcracker.cloud.maas.client.api.MaaSAPIClient;
import com.netcracker.cloud.maasfailover.probe.MaasKafkaProbe;
import com.netcracker.cloud.maasfailover.probe.MaasRabbitProbe;
import com.netcracker.cloud.maasfailover.probe.MaasWatchProbe;
import com.netcracker.cloud.maasfailover.probe.MaasProbe;
import com.netcracker.cloud.maasfailover.workload.WorkloadRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/** Wiring for the framework-free probes and runner in test-service-common. */
@Configuration
public class WorkloadConfiguration {

    @Bean
    public MaasKafkaProbe maasKafkaProbe(MaaSAPIClient maas) {
        return new MaasKafkaProbe(maas);
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
    public WorkloadRunner workloadRunner(List<MaasProbe> probes) {
        return new WorkloadRunner(probes);
    }
}
