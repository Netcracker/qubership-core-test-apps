package com.netcracker.it.spring;

import com.netcracker.cloud.junit.cloudcore.extension.annotations.EnableExtension;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.PortForward;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.Value;
import com.netcracker.it.common.ConsulITBase;

import java.net.URL;

@EnableExtension
public class ConsulIT extends ConsulITBase {

    @PortForward(serviceName = @Value(Const.MESH_TEST_SERVICE_SPRING_V1))
    private static URL springServiceUrl;

    @Override
    protected URL getServiceUrl() {
        return springServiceUrl;
    }
}
