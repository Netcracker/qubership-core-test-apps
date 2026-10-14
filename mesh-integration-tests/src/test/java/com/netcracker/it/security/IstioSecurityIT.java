package com.netcracker.it.security;

import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Asserts that the Istio control plane and data plane match their documented security posture.
 *
 * <p>istiod must meet the full baseline. The CNI agent and ztunnel run with elevated privileges by
 * design, so they are held to the documented exception instead: never fully privileged, still drop
 * all capabilities first, and add only the capabilities they require by design. The tests fail if a
 * component gains privilege beyond that allowed set.
 *
 * <p>The namespace defaults to {@code istio-system} and is overridable with
 * {@code -Dsecurity.test.istio.namespace}.
 */
@Tag("Security")
class IstioSecurityIT {

    /** Capabilities the CNI agent needs to install network configuration on the node. */
    private static final Set<String> CNI_ALLOWED_CAPABILITIES =
            Set.of("NET_ADMIN", "NET_RAW", "SYS_PTRACE", "SYS_ADMIN", "DAC_OVERRIDE");

    /** Capabilities ztunnel needs for TPROXY redirection and entering pod network namespaces. */
    private static final Set<String> ZTUNNEL_ALLOWED_CAPABILITIES =
            Set.of("NET_ADMIN", "SYS_ADMIN", "NET_RAW");

    private static KubernetesClient client;
    private static String namespace;

    @BeforeAll
    static void connect() {
        client = KubeSupport.newClient();
        namespace = KubeSupport.istioNamespace();
        boolean istioInstalled = KubeSupport.workloads(client, namespace).stream()
                .anyMatch(workload -> workload.name().equals("istiod"));
        assumeTrue(istioInstalled,
                "Istio is not installed in '" + namespace + "'; skipping Istio security tests.");
    }

    @AfterAll
    static void close() {
        if (client != null) {
            client.close();
        }
    }

    @Test
    void istiodMeetsFullBaseline() {
        Workload istiod = require(Workload.Kind.DEPLOYMENT, "istiod");
        List<String> violations = SecurityRequirements.checkStrict(istiod);
        assertTrue(violations.isEmpty(),
                () -> "istiod must meet the full security baseline:\n  - " + String.join("\n  - ", violations));
    }

    @Test
    void cniAgentStaysWithinDocumentedException() {
        Workload cni = require(Workload.Kind.DAEMON_SET, "istio-cni-node");
        List<String> violations = SecurityRequirements.checkDocumentedException(cni, CNI_ALLOWED_CAPABILITIES, true);
        assertTrue(violations.isEmpty(),
                () -> "istio-cni-node exceeds its documented security exception:\n  - "
                        + String.join("\n  - ", violations));
    }

    @Test
    void ztunnelStaysWithinDocumentedException() {
        Workload ztunnel = require(Workload.Kind.DAEMON_SET, "ztunnel");
        List<String> violations =
                SecurityRequirements.checkDocumentedException(ztunnel, ZTUNNEL_ALLOWED_CAPABILITIES, false);
        assertTrue(violations.isEmpty(),
                () -> "ztunnel exceeds its documented security exception:\n  - "
                        + String.join("\n  - ", violations));
    }

    private Workload require(Workload.Kind kind, String name) {
        Workload workload = KubeSupport.workloads(client, namespace).stream()
                .filter(candidate -> candidate.kind() == kind && candidate.name().equals(name))
                .findFirst()
                .orElse(null);
        assertNotNull(workload, () -> kind + " '" + name + "' not found in namespace '" + namespace
                + "'. Confirm Istio is installed, or set -Dsecurity.test.istio.namespace.");
        return workload;
    }
}
