package com.netcracker.it.spring.utils;

import com.netcracker.cloud.junit.cloudcore.extension.service.Endpoint;
import com.netcracker.cloud.junit.cloudcore.extension.service.NetSocketAddress;
import com.netcracker.cloud.junit.cloudcore.extension.service.PortForwardService;
import com.netcracker.cloud.junit.cloudcore.extension.service.ServicePortForwardParams;
import lombok.Getter;

import java.net.URL;

public class ClosablePortForward implements CloseableUrl {
    public static final int DEFAULT_CONTAINER_PORT = 8080;

    private final PortForwardService portForwardService;

    @Getter
    private final URL url;

    private NetSocketAddress address;

    public ClosablePortForward(PortForwardService portForwardService, String namespace, String serviceName, int containerPort) {
        this.portForwardService = portForwardService;
        ServicePortForwardParams<NetSocketAddress> params = ServicePortForwardParams.builder(serviceName, containerPort)
                .namespace(namespace)
                .build();
        address = portForwardService.portForward(params);
        url =  address.toHttpUrl();
    }

    public ClosablePortForward(PortForwardService portForwardService, String namespace, String serviceName) {
        this(portForwardService, namespace, serviceName, DEFAULT_CONTAINER_PORT);
    }

    @Override
    public void close() {
        portForwardService.closePortForward(new Endpoint(address.getHostString(), address.getPort()));
    }
}
