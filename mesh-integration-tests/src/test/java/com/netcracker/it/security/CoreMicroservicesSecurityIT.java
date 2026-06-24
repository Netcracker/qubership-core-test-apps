package com.netcracker.it.security;

import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.util.List;
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
 * {@code -Dsecurity.test.namespace}; exclude specific workloads with {@code -Dsecurity.test.exclude}.
 */
@Tag("Security")
class CoreMicroservicesSecurityIT {

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
                .filter(workload -> !KubeSupport.excludedWorkloads().contains(workload.name()))
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
}
