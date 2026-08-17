package com.netcracker.it.storage.scenario;

import com.netcracker.cloud.junit.cloudcore.extension.annotations.IntValue;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.PortForward;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.Value;

import java.net.URL;

/** Scenarios driven against the Quarkus application, which uses the Quarkus MaaS extension. */
public abstract class QuarkusStorageITBase extends StorageITBase {

    @PortForward(serviceName = @Value("storage-test-service-quarkus"), port = @IntValue(8080))
    protected static URL quarkusAppUrl;

    @Override
    protected URL appUrl() {
        return quarkusAppUrl;
    }
}
