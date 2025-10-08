package com.netcracker.it.meshtestservicespring;

import com.netcracker.cloud.junit.cloudcore.extension.annotations.Cloud;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.EnableExtension;
import com.netcracker.cloud.junit.cloudcore.extension.service.PortForwardService;
import com.netcracker.it.meshtestservicespring.utils.ClosablePortForward;

@EnableExtension
public abstract class TestWithPortForward {

    @Cloud
    protected PortForwardService portForwardService;

    public ClosablePortForward createPortForward(final String namespace, final String serviceName, final int containerPort) {
        return new ClosablePortForward(portForwardService, namespace, serviceName, containerPort);
    }
}
