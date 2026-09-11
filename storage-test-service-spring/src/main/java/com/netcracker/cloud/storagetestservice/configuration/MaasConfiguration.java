package com.netcracker.cloud.storagetestservice.configuration;

import com.netcracker.cloud.maas.client.api.MaaSAPIClient;
import com.netcracker.cloud.maas.client.impl.MaaSAPIClientImpl;
import com.netcracker.cloud.security.core.auth.DummyM2MManager;
import com.netcracker.cloud.security.core.auth.M2MManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The MaaS client under test. */
@Configuration
public class MaasConfiguration {

    /**
     * Falls back to the dummy manager when the platform did not contribute one, so the application
     * still starts outside a cluster; in a cluster the real bean wins.
     */
    // close() is inferred: the client is AutoCloseable
    @Bean
    public MaaSAPIClient maaSAPIClient(ObjectProvider<M2MManager> m2mManager) {
        M2MManager manager = m2mManager.getIfAvailable(DummyM2MManager::new);
        return new MaaSAPIClientImpl(() -> manager.getToken().getTokenValue());
    }
}
