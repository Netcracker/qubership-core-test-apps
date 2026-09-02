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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks that a Quarkus service logs in to Consul with the projected service account token of its pod and reads its
 * configuration with the token Consul issued: it has to report a token of its own and serve a property seeded before
 * the pod started.
 *
 * <p>The login library is shared between the stacks, but the configuration around it, the transport and the reader of
 * the properties are Quarkus code, which is why this way is checked on each stack separately.
 */
@DisplayName("The Quarkus service logs in to Consul with its projected token and reads its properties")
class QuarkusServiceKubernetesLoginIT {

    private static final TestService SERVICE = TestService.QUARKUS;

    private static final String NAMESPACE = "consul-login-test-quarkus";

    private static final String AUTH_METHOD = "consul-login-test-service-quarkus";
    private static final String POLICY = "consul-login-test-quarkus-read";
    private static final String ROLE = "consul-login-test-quarkus-reader";
    private static final String KV_PREFIX = "config/" + NAMESPACE + "/";
    private static final String MARKER_KEY = KV_PREFIX + "application/service.marker";
    private static final String MARKER_VALUE = "marker-read-by-quarkus";

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
    @DisplayName("The service reports a Consul token of its own and the property it read")
    void serviceLogsInAndReadsItsProperties() {
        JsonNode status = TestService.awaitLoginStatus(servicePortForward);

        assertEquals("kubernetes", status.path("loginMode").asText(), "login mode");
        assertTrue(status.path("tokenPresent").asBoolean(), "the service holds a Consul token");
        assertEquals(MARKER_VALUE, status.path("consulMarker").asText(), "property read from Consul");
        assertTrue(ConsulAcl.issuedTokenCount(consul, AUTH_METHOD) > 0,
                "Consul issued a token through the auth method of the service");
    }

    private static Map<String, String> serviceEnvironment() {
        return Map.of(
                "CLOUD_NAMESPACE", NAMESPACE,
                "MICROSERVICE_NAME", SERVICE.serviceName(),
                "CONSUL_URL", Cluster.CONSUL_IN_CLUSTER_URL + "/",
                "CONSUL_LOGIN_MODE", "kubernetes",
                "CONSUL_LOGIN_AUTH_METHOD", AUTH_METHOD,
                "CONSUL_LOGIN_AUDIENCE", ProjectedToken.AUDIENCE);
    }
}
