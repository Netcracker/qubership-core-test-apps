package com.netcracker.cloud.storagetestservice.configuration;

import com.netcracker.cloud.maas.client.api.MaaSAPIClient;
import com.netcracker.cloud.maas.client.impl.MaaSAPIClientImpl;
import com.netcracker.cloud.security.core.auth.M2MManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The MaaS client under test, wired the way the library's README describes. */
@Configuration
public class MaasConfiguration {

    @Bean(destroyMethod = "close")
    public MaaSAPIClient maaSAPIClient(M2MManager m2mManager) {
        return new MaaSAPIClientImpl(() -> m2mManager.getToken().getTokenValue());
    }
}
