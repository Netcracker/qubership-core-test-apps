package com.netcracker.it.storage.scenario;

import java.util.stream.Stream;

final class Namespaces {

    private Namespaces() {
    }

    /** Namespace of the application and the core services, as the runner already passes it. */
    static String application() {
        return Stream.of("ORIGIN_NAMESPACE", "env.cloud-namespace", "clouds.cloud.namespaces.namespace")
                .map(System::getProperty)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "the application namespace is not set; run through run-it/run-integration-tests.sh"));
    }
}
