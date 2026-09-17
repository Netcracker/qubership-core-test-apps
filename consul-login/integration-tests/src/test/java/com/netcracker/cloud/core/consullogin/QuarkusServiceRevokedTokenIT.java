package com.netcracker.cloud.core.consullogin;

import com.fasterxml.jackson.databind.JsonNode;
import com.netcracker.cloud.core.consullogin.stand.Cluster;
import com.netcracker.cloud.core.consullogin.stand.ConsulAcl;
import com.netcracker.cloud.core.consullogin.stand.ConsulClient;
import com.netcracker.cloud.core.consullogin.stand.ProjectedToken;
import com.netcracker.cloud.core.consullogin.stand.StandDump;
import com.netcracker.cloud.core.consullogin.stand.TestService;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.LocalPortForward;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fails when the Quarkus service keeps a token Consul no longer resolves.
 *
 * <p>Consul answers 403 with {@code ACL not found} for a token it has forgotten, which is what a reinitialized ACL
 * state leaves every pod holding. The service has to log in again and go on reading its configuration in the pod it
 * already runs in, so the pod name and the restart count are read alongside the token: a restarted pod reports
 * another token too, for a reason this scenario does not cover.
 *
 * <p>The auth method here carries no MaxTokenTTL, so the tokens Consul issues through it never expire and the
 * scheduled relogin has nothing to fire on. The relogin has to come from the rejected token itself.
 *
 * <p>The login library is shared between the stacks, but the configuration around it, the transport and the reader
 * of the properties are Quarkus code, which is why the recovery is checked on each stack separately.
 *
 * <p>The wait prints the last {@link ServiceState} it read. A token fingerprint that stayed the same is the defect
 * this scenario reproduces; a pod name or a restart count that moved is a crash loop and a different failure.
 */
@DisplayName("The Quarkus service logs in again after Consul forgets its token")
class QuarkusServiceRevokedTokenIT {

    private static final TestService SERVICE = TestService.QUARKUS;

    private static final String NAMESPACE = "consul-login-test-quarkus-revoked";

    private static final String AUTH_METHOD = "consul-login-test-quarkus-revoked";
    private static final String POLICY = "consul-login-test-quarkus-revoked-read";
    private static final String ROLE = "consul-login-test-quarkus-revoked-reader";
    private static final String KV_PREFIX = "config/" + NAMESPACE + "/";
    private static final String MARKER_KEY = KV_PREFIX + "application/service.marker";
    private static final String MARKER_VALUE = "marker-read-by-quarkus-before-the-revocation";
    private static final String CHANGED_MARKER_VALUE = "marker-read-by-quarkus-after-the-revocation";

    /** What a state reports for a value the service or the cluster did not give it. */
    private static final String UNAVAILABLE = "unavailable";

    /** How long the recovery may take: the relogin the rejected token forces, and one configuration poll after it. */
    private static final Duration RECOVERY_BUDGET = Duration.ofMinutes(4);

    private static KubernetesClient kubernetes;
    private static LocalPortForward consulPortForward;
    private static LocalPortForward servicePortForward;
    private static ConsulClient consul;
    private static String bindingRuleId;

    @RegisterExtension
    static final StandDump standDump = StandDump.onFailure(() -> consul, () -> kubernetes, NAMESPACE);

    @BeforeAll
    static void prepareStand() {
        kubernetes = Cluster.newClient();
        consulPortForward = Cluster.forwardConsulPort(kubernetes);
        consul = new ConsulClient("http://localhost:" + consulPortForward.getLocalPort(),
                Cluster.readBootstrapToken(kubernetes));

        consul.put("/v1/kv/" + MARKER_KEY, MARKER_VALUE).requireSuccess("seeding the marker key");
        ConsulAcl.createReadPolicy(consul, POLICY, KV_PREFIX);
        ConsulAcl.createRole(consul, ROLE, POLICY);
        ConsulAcl.createKubernetesAuthMethod(consul, kubernetes, AUTH_METHOD);
        bindingRuleId = ConsulAcl.createBindingRule(consul, AUTH_METHOD,
                "value.namespace==\"" + NAMESPACE + "\"", ROLE);

        SERVICE.deploy(kubernetes, NAMESPACE, serviceEnvironment(), true);
        servicePortForward = SERVICE.forwardPort(kubernetes, NAMESPACE);
    }

    @AfterAll
    static void cleanUpStand() {
        if (consul != null) {
            ConsulAcl.deleteIssuedTokens(consul, AUTH_METHOD);
            if (bindingRuleId != null) {
                consul.delete("/v1/acl/binding-rule/" + bindingRuleId);
            }
            consul.delete("/v1/acl/auth-method/" + AUTH_METHOD);
            ConsulAcl.deleteRole(consul, ROLE);
            ConsulAcl.deletePolicy(consul, POLICY);
            consul.delete("/v1/kv/" + KV_PREFIX + "?recurse=true");
        }
        Cluster.tearDown(kubernetes, NAMESPACE, servicePortForward, consulPortForward);
    }

    @Test
    @DisplayName("The service reports another token and the changed property in the pod it started in")
    void serviceRelogsInAfterItsTokenIsDeletedAndStaysInTheSamePod() {
        TestService.awaitLoginStatus(servicePortForward);
        ServiceState before = currentState();
        assertNotEquals("NONE", before.tokenFingerprint(), "token the service held before the deletion");
        assertEquals(MARKER_VALUE, before.marker(), "property read from Consul before the deletion");

        ConsulAcl.deleteIssuedTokens(consul, AUTH_METHOD);
        consul.put("/v1/kv/" + MARKER_KEY, CHANGED_MARKER_VALUE).requireSuccess("changing the marker key");

        Awaitility.await("the service reports another token and the changed property in the same pod")
                .atMost(RECOVERY_BUDGET)
                .pollInterval(Duration.ofSeconds(5))
                .ignoreExceptions()
                .until(QuarkusServiceRevokedTokenIT::currentState, state -> recovered(before, state));

        int issued = ConsulAcl.issuedTokenCount(consul, AUTH_METHOD);
        assertTrue(issued > 0, "tokens the auth method issued after the deletion: " + issued);
    }

    /**
     * The recovery the scenario waits for. The pod name and the restart count hold what the token alone cannot: a
     * restarted pod also reports a token the deleted one does not match.
     */
    private static boolean recovered(ServiceState before, ServiceState now) {
        return !now.tokenFingerprint().equals(before.tokenFingerprint())
                && CHANGED_MARKER_VALUE.equals(now.marker())
                && now.podName().equals(before.podName())
                && now.restartCount() == before.restartCount();
    }

    private static ServiceState currentState() {
        JsonNode status = TestService.loginStatus(servicePortForward);
        Optional<Pod> pod = servicePod();
        return new ServiceState(
                status == null ? UNAVAILABLE : status.path("tokenFingerprint").asText(),
                status == null ? UNAVAILABLE : status.path("consulMarker").asText(),
                pod.map(running -> running.getMetadata().getName()).orElse(UNAVAILABLE),
                pod.map(QuarkusServiceRevokedTokenIT::restartCount).orElse(-1));
    }

    private static Optional<Pod> servicePod() {
        return kubernetes.pods().inNamespace(NAMESPACE).withLabel("app", SERVICE.serviceName())
                .list().getItems().stream()
                .findFirst();
    }

    private static int restartCount(Pod pod) {
        List<ContainerStatus> statuses = pod.getStatus().getContainerStatuses();
        if (statuses == null || statuses.isEmpty()) {
            return -1;
        }
        return Objects.requireNonNullElse(statuses.get(0).getRestartCount(), -1);
    }

    private static Map<String, String> serviceEnvironment() {
        return Map.of(
                "CLOUD_NAMESPACE", NAMESPACE,
                "MICROSERVICE_NAME", SERVICE.serviceName(),
                "CONSUL_URL", Cluster.CONSUL_IN_CLUSTER_URL + "/",
                "CONSUL_AUTH_MODE", "kubernetes",
                "CONSUL_AUTH_METHOD", AUTH_METHOD,
                "CONSUL_AUTH_AUDIENCE", ProjectedToken.AUDIENCE);
    }

    /**
     * One poll of the recovery: what the service reports about its token and its property, and which pod reported
     * it. Awaitility prints this record when the wait runs out, so a red run names the state it stopped at.
     */
    private record ServiceState(String tokenFingerprint, String marker, String podName, int restartCount) {
    }
}
