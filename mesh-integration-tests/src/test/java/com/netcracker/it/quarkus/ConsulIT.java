package com.netcracker.it.quarkus;

import com.netcracker.cloud.junit.cloudcore.extension.annotations.EnableExtension;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.PortForward;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.Value;
import com.netcracker.it.common.ConsulITBase;

import java.net.URL;

@EnableExtension
public class ConsulIT extends ConsulITBase {

    @PortForward(serviceName = @Value("mesh-test-service-quarkus-v1"))
    private static URL quarkusServiceUrl;

    @Override
    protected URL getServiceUrl() {
        return quarkusServiceUrl;
    }

}
