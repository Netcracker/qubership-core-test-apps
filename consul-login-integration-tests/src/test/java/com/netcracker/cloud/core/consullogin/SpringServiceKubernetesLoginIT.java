package com.netcracker.cloud.core.consullogin;

import com.fasterxml.jackson.databind.JsonNode;
import com.netcracker.cloud.core.consullogin.stand.Cluster;
import com.netcracker.cloud.core.consullogin.stand.ConsulAcl;
import com.netcracker.cloud.core.consullogin.stand.ConsulClient;
import com.netcracker.cloud.core.consullogin.stand.ProjectedToken;
import com.netcracker.cloud.core.consullogin.stand.StandDump;
import com.netcracker.cloud.core.consullogin.stand.TestService;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.LocalPortForward;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
 * <p>A second check covers the relogin. The auth method caps the token lifetime at a minute, so the service has to
 * log in again while it runs and keep serving the property afterwards, including a value changed in the meantime.
 * Both checks share one pod: the configuration is the same, and a second deployment would cost a pod start without
 * proving anything.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("The Spring service logs in to Consul with its projected token and reads its properties")
class SpringServiceKubernetesLoginIT {

    private static final TestService SERVICE = TestService.SPRING;

    private static final String NAMESPACE = "consul-login-test";

    private static final String AUTH_METHOD = "consul-login-test-service";
    private static final String POLICY = "consul-login-test-service-read";
    private static final String ROLE = "consul-login-test-service-reader";
    private static final String KV_PREFIX = "config/" + NAMESPACE + "/";
    private static final String MARKER_KEY = KV_PREFIX + "application/service.marker";
    private static final String MARKER_VALUE = "marker-read-with-the-issued-token";
    private static final String CHANGED_MARKER_VALUE = "marker-changed-after-the-relogin";

    /** The shortest lifetime Consul accepts, so that the scheduled relogin happens inside a test run. */
    private static final String TOKEN_TTL = "1m";

    /**
     * A pod logs in twice while it starts, once in the ConfigData phase and once for the application context. Only a
     * token issued well after those counts as the scheduled relogin.
     */
    private static final Duration SETTLED_AFTER = Duration.ofSeconds(30);

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
        ConsulAcl.createKubernetesAuthMethod(consul, kubernetes, AUTH_METHOD, TOKEN_TTL);
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
    @Order(1)
    @DisplayName("The service reports a Consul token of its own and the property it read")
    void serviceLogsInAndReadsItsProperties() {
        JsonNode status = TestService.awaitLoginStatus(servicePortForward);

        assertEquals("kubernetes", status.path("loginMode").asText(), "login mode");
        assertTrue(status.path("tokenPresent").asBoolean(), "the service holds a Consul token");
        assertEquals(MARKER_VALUE, status.path("consulMarker").asText(), "property read from Consul");
    }

    @Test
    @Order(2)
    @DisplayName("The service relogs in when its token expires and reads a value changed since")
    void serviceRelogsInAndReadsTheChangedValue() {
        Instant issuedBefore = ConsulAcl.latestIssuedAt(consul, AUTH_METHOD);
        consul.put("/v1/kv/" + MARKER_KEY, CHANGED_MARKER_VALUE).requireSuccess("changing the marker key");

        Awaitility.await("the service relogs in through the same auth method")
                .atMost(Duration.ofMinutes(3))
                .pollInterval(Duration.ofSeconds(5))
                .ignoreExceptions()
                .until(() -> ConsulAcl.latestIssuedAt(consul, AUTH_METHOD).isAfter(issuedBefore.plus(SETTLED_AFTER)));

        Awaitility.await("the service serves the changed property")
                .atMost(Duration.ofMinutes(2))
                .pollInterval(Duration.ofSeconds(5))
                .ignoreExceptions()
                .until(() -> {
                    JsonNode status = TestService.loginStatus(servicePortForward);
                    return status != null && CHANGED_MARKER_VALUE.equals(status.path("consulMarker").asText());
                });
    }

    private static Map<String, String> serviceEnvironment() {
        return Map.of(
                "CLOUD_NAMESPACE", NAMESPACE,
                "MICROSERVICE_NAME", SERVICE.serviceName(),
                "CONSUL_HOST", "consul-consul-server.consul",
                "CONSUL_LOGIN_MODE", "kubernetes",
                "CONSUL_LOGIN_AUTH_METHOD", AUTH_METHOD,
                "CONSUL_LOGIN_AUDIENCE", ProjectedToken.AUDIENCE);
    }

}
