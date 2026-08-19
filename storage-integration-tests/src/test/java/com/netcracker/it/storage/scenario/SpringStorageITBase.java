package com.netcracker.it.storage.scenario;

import com.netcracker.cloud.junit.cloudcore.extension.annotations.IntValue;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.PortForward;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.Value;

import java.net.URL;

/** Scenarios driven against the Spring application, which uses the Java client libraries. */
public abstract class SpringStorageITBase extends StorageITBase {

    @PortForward(serviceName = @Value("storage-test-service-spring"), port = @IntValue(8080))
    protected static URL springAppUrl;

    @Override
    protected URL appUrl() {
        return springAppUrl;
    }
}
