package com.netcracker.cloud.core.consullogin.service;

import com.netcracker.cloud.security.core.auth.M2MManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Publishes the same stand-in for the application context, next to what {@link M2MManagerBootstrapper} registers for
 * the ConfigData phase. The autoconfiguration of the transport builds the {@code TokenStorage} the service serves its
 * status from, and it falls back to {@code DummyM2MManager} when no bean is present, so without this one the m2m way
 * would reach Consul with a token it rejects.
 */
@Configuration
public class ServiceM2MConfiguration {

    @Bean
    public M2MManager m2mManager() {
        return new StandInM2MManager();
    }
}
