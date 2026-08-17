package com.netcracker.it.storage.scenario;

import com.netcracker.cloud.junit.cloudcore.extension.annotations.IntValue;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.PortForward;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.Value;

import java.net.URL;
import java.util.stream.Stream;

/**
 * Scenarios driven against the Quarkus application.
 *
 * <p>The Quarkus extension wraps the same Java client the Spring application drives, so what is new
 * here is the CDI wiring, not the retry logic. This platform therefore runs one fault rather than
 * the whole sweep, and leaves the leak scenario to the Spring classes.
 */
public abstract class QuarkusStorageITBase extends StorageITBase {

    @PortForward(serviceName = @Value("storage-test-service-quarkus"), port = @IntValue(8080))
    protected static URL quarkusAppUrl;

    @Override
    protected URL appUrl() {
        return quarkusAppUrl;
    }

    @Override
    Stream<Fault> faults() {
        return Stream.of(profile().primaryFault());
    }

    @Override
    protected boolean checksResourceHygiene() {
        return false;
    }
}
