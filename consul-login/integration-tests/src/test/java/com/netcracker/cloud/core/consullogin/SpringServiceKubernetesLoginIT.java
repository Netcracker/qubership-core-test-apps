package com.netcracker.cloud.core.consullogin;

import com.fasterxml.jackson.databind.JsonNode;
import com.netcracker.cloud.core.consullogin.stand.Cluster;
import com.netcracker.cloud.core.consullogin.stand.ConsulAcl;
import com.netcracker.cloud.core.consullogin.stand.ConsulAclResource;
import com.netcracker.cloud.core.consullogin.stand.ConsulClient;
import com.netcracker.cloud.core.consullogin.stand.StandDump;
import com.netcracker.cloud.core.consullogin.stand.TestService;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.LocalPortForward;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.TestMethodOrder;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks that a Spring service logs in to Consul with the projected service account token of its pod and reads its
 * configuration with the token Consul issued: it has to report a token of its own and serve a property seeded before
 * the pod started.
 *
 * <p>A second check covers the relogin: the service has to log in again when its token expires and keep serving the
 * property afterwards, including a value changed in the meantime. Both checks share one pod: the configuration is the
 * same, and a second deployment would cost a pod start without proving anything.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("The Spring service logs in to Consul with its projected token and reads its properties")
class SpringServiceKubernetesLoginIT {

    private static final TestService SERVICE = TestService.SPRING;

    private static final String NAMESPACE = "consul-login-test";

    private static final String ROLE = ConsulAclResource.roleName(NAMESPACE);
    private static final String KV_PREFIX = "config/" + NAMESPACE + "/";
    private static final String MARKER_KEY = KV_PREFIX + "application/service.marker";
    private static final String MARKER_VALUE = "marker-read-with-the-issued-token";
    private static final String CHANGED_MARKER_VALUE = "marker-changed-after-the-relogin";

    /**
     * A pod logs in twice while it starts, once in the ConfigData phase and once for the application context. Only a
     * token issued well after those counts as the scheduled relogin.
     */
    private static final Duration SETTLED_AFTER = Duration.ofSeconds(30);

    private static KubernetesClient kubernetes;
    private static LocalPortForward consulPortForward;
    private static LocalPortForward servicePortForward;
    private static ConsulClient consul;

    @RegisterExtension
    static final StandDump standDump = StandDump.onFailure(() -> consul, () -> kubernetes, NAMESPACE);

    @BeforeAll
    static void prepareStand() {
        kubernetes = Cluster.newClient();
        consulPortForward = Cluster.forwardConsulPort(kubernetes);
        consul = new ConsulClient("http://localhost:" + consulPortForward.getLocalPort(),
                Cluster.readBootstrapToken(kubernetes));

        consul.put("/v1/kv/" + MARKER_KEY, MARKER_VALUE).requireSuccess("seeding the marker key");
        ConsulAclResource.apply(kubernetes, NAMESPACE, SERVICE.serviceName(), KV_PREFIX);
        SERVICE.deploy(kubernetes, NAMESPACE, serviceEnvironment(), true);
        servicePortForward = SERVICE.forwardPort(kubernetes, NAMESPACE);
    }

    @AfterAll
    static void cleanUpStand() {
        if (consul != null) {
            consul.delete("/v1/kv/" + KV_PREFIX + "?recurse=true");
        }
        Cluster.tearDown(kubernetes, NAMESPACE, servicePortForward, consulPortForward);
    }

    @Test
    @Order(1)
    @DisplayName("The service reports a Consul token of its own and the property it read")
    void serviceLogsInAndReadsItsProperties() {
        JsonNode status = TestService.awaitLoginStatus(servicePortForward);

        assertEquals("k8s", status.path("loginMode").asText(), "login mode");
        assertTrue(status.path("tokenPresent").asBoolean(), "the service holds a Consul token");
        assertEquals(MARKER_VALUE, status.path("consulMarker").asText(), "property read from Consul");
        assertTrue(ConsulAcl.issuedTokenCount(consul, ConsulAclResource.AUTH_METHOD, ROLE) > 0,
                "Consul issued a token with the role of the namespace through the auth method of the operator");
    }

    @Test
    @Order(2)
    @Disabled("applications-k8s-m2m sets no MaxTokenTTL, so the token never expires; open question to qubership-consul")
    @DisplayName("The service relogs in when its token expires and reads a value changed since")
    void serviceRelogsInAndReadsTheChangedValue() {
        Instant issuedBefore = latestIssuedAt();
        consul.put("/v1/kv/" + MARKER_KEY, CHANGED_MARKER_VALUE).requireSuccess("changing the marker key");

        Awaitility.await("the service relogs in through the same auth method")
                .atMost(Duration.ofMinutes(3))
                .pollInterval(Duration.ofSeconds(5))
                .ignoreExceptions()
                .until(() -> latestIssuedAt().isAfter(issuedBefore.plus(SETTLED_AFTER)));

        Awaitility.await("the service serves the changed property")
                .atMost(Duration.ofMinutes(2))
                .pollInterval(Duration.ofSeconds(5))
                .ignoreExceptions()
                .until(() -> {
                    JsonNode status = TestService.loginStatus(servicePortForward);
                    return status != null && CHANGED_MARKER_VALUE.equals(status.path("consulMarker").asText());
                });
    }

    private static Instant latestIssuedAt() {
        return ConsulAcl.latestIssuedAt(consul, ConsulAclResource.AUTH_METHOD, ROLE);
    }

    private static Map<String, String> serviceEnvironment() {
        return Map.of(
                "CLOUD_NAMESPACE", NAMESPACE,
                "MICROSERVICE_NAME", SERVICE.serviceName(),
                "CONSUL_HOST", Cluster.CONSUL_IN_CLUSTER_HOST,
                "CONSUL_AUTH_MODE", "k8s");
    }

}
