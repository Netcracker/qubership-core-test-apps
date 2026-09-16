package com.netcracker.it.maas.scenario;

import com.netcracker.cloud.junit.cloudcore.extension.annotations.IntValue;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.PortForward;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.Value;

import java.net.URL;

/** Scenarios driven against the Go application, which uses the Go MaaS client libraries. */
public abstract class GoFailoverITBase extends FailoverITBase {

    @PortForward(serviceName = @Value("maas-failover-test-service-go"), port = @IntValue(8080))
    protected static URL goAppUrl;

    @Override
    protected URL appUrl() {
        return goAppUrl;
    }
}
