package com.netcracker.it.security;

import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Asserts that every core microservice deployed in the test namespace meets the full container
 * security baseline. One dynamic test is generated per workload, so a failure names the exact
 * Deployment, StatefulSet, or DaemonSet and lists its violations.
 *
 * <p>Istio-injected sidecars and init containers are skipped here; they are covered by
 * {@link IstioSecurityIT}. The namespace defaults to {@code core} and is overridable with
 * {@code -Dsecurity.test.namespace}; exclude extra workloads with {@code -Dsecurity.test.exclude}.
 *
 * <p>The mesh-test services and the test stateful/daemon-set fixtures are throwaway test objects, not
 * platform microservices, so they are excluded by default (matched by name prefix to cover version
 * suffixes such as {@code -v1}).
 */
@Tag("Security")
class CoreMicroservicesSecurityIT {

    /** Name prefixes of test fixtures excluded by default. */
    private static final Set<String> DEFAULT_EXCLUDED_PREFIXES = Set.of(
            "mesh-test-service-spring",
            "mesh-test-service-quarkus",
            "mesh-test-service-go",
            "test-stateful-set",
            "test-daemon-set");

    private static KubernetesClient client;
    private static String namespace;

    @BeforeAll
    static void connect() {
        client = KubeSupport.newClient();
        namespace = KubeSupport.coreNamespace();
    }

    @AfterAll
    static void close() {
        if (client != null) {
            client.close();
        }
    }

    @Test
    void namespaceContainsWorkloads() {
        List<Workload> workloads = KubeSupport.workloads(client, namespace);
        assertFalse(workloads.isEmpty(), () -> "No Deployments, StatefulSets, or DaemonSets found in namespace '"
                + namespace + "'. Set -Dsecurity.test.namespace to the namespace where the core microservices run.");
    }

    @TestFactory
    Stream<DynamicTest> coreMicroservicesMeetSecurityBaseline() {
        return KubeSupport.workloads(client, namespace).stream()
                .filter(workload -> !isExcluded(workload.name()))
                .map(workload -> DynamicTest.dynamicTest(
                        workload.id() + " meets the container security baseline",
                        () -> {
                            List<String> violations = SecurityRequirements.checkStrict(
                                    workload, SecurityRequirements.ISTIO_INJECTED_CONTAINERS);
                            assertTrue(violations.isEmpty(), () -> "Security requirement violations for "
                                    + workload.id() + " in namespace " + namespace + ":\n  - "
                                    + String.join("\n  - ", violations));
                        }));
    }

    private static boolean isExcluded(String name) {
        boolean defaultExcluded = DEFAULT_EXCLUDED_PREFIXES.stream().anyMatch(name::startsWith);
        return defaultExcluded || KubeSupport.excludedWorkloads().contains(name);
    }
}
