package com.netcracker.it.meshtestservicespring.utils;

import com.netcracker.cloud.junit.cloudcore.extension.service.Endpoint;
import com.netcracker.cloud.junit.cloudcore.extension.service.NetSocketAddress;
import com.netcracker.cloud.junit.cloudcore.extension.service.PortForwardParams;
import com.netcracker.cloud.junit.cloudcore.extension.service.PortForwardService;
import lombok.Getter;

import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;

public class ClosablePortForward implements CloseableUrl {
    public static final int DEFAULT_CONTAINER_PORT = 8080;

    private final PortForwardService portForwardService;

    @Getter
    private final URL url;

    private NetSocketAddress address;

    public ClosablePortForward(PortForwardService portForwardService, String namespace, String serviceName, int containerPort) {
        this.portForwardService = portForwardService;

        PortForwardParams params = new PortForwardParams(serviceName, containerPort).withNamespace(namespace);
        address = portForwardService.portForward(params);
        String endpoint = address.getEndpoint();
        try {
            url =  new java.net.URI(endpoint).toURL();
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    public ClosablePortForward(PortForwardService portForwardService, String namespace, String serviceName) {
        this(portForwardService, namespace, serviceName, DEFAULT_CONTAINER_PORT);
    }

    @Override
    public void close() {
        portForwardService.closePortForward(new Endpoint(address.getHostString(), address.getPort()));
    }
}
