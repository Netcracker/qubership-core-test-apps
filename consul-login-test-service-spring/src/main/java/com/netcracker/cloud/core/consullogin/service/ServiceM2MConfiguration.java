package com.netcracker.cloud.core.consullogin.service;

import com.netcracker.cloud.security.core.auth.M2MManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Publishes the same stand-in for the application context. The autoconfiguration of the transport modules falls back
 * to {@code DummyM2MManager} only when no bean is present, so this one wins and the m2m way works in every entry
 * point, not just in the ConfigData phase.
 */
@Configuration
public class ServiceM2MConfiguration {

    @Bean
    public M2MManager m2mManager() {
        return ServiceM2M.standIn();
    }
}
