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
 * Checks that a Go service logs in to Consul with the projected service account token of its pod and reads its
 * configuration with the token Consul issued: it has to serve a property seeded before the pod started, which under a
 * deny-by-default policy it can only have read through a token of its own.
 *
 * <p>The Go library is a second implementation of the same login, written apart from the Java one, and its settings
 * arrive by a route of their own: environment variables mapped onto properties by {@code configloader}. The status
 * therefore also reports the way, the auth method and the audience the service ended up configured for, so that a
 * mapping that silently fell back to the defaults is read off the failure rather than guessed at.
 */
@DisplayName("The Go service logs in to Consul with its projected token and reads its properties")
class GoServiceKubernetesLoginIT {

    private static final TestService SERVICE = TestService.GO;

    private static final String NAMESPACE = "consul-login-test-go";

    private static final String AUTH_METHOD = "consul-login-test-service-go";
    private static final String POLICY = "consul-login-test-go-read";
    private static final String ROLE = "consul-login-test-go-reader";
    private static final String KV_PREFIX = "config/" + NAMESPACE + "/";
    private static final String MARKER_KEY = KV_PREFIX + "application/service.marker";
    private static final String MARKER_VALUE = "marker-read-by-go";

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
    @DisplayName("The service reports the settings it was configured with and the property it read")
    void serviceLogsInAndReadsItsProperties() {
        JsonNode status = TestService.awaitLoginStatus(servicePortForward);

        assertEquals("kubernetes", status.path("loginMode").asText(), "login mode");
        assertEquals(AUTH_METHOD, status.path("authMethod").asText(), "auth method the settings named");
        assertEquals(ProjectedToken.AUDIENCE, status.path("audience").asText(), "audience the settings named");
        assertEquals(MARKER_VALUE, status.path("consulMarker").asText(), "property read from Consul");
        assertTrue(ConsulAcl.issuedTokenCount(consul, AUTH_METHOD) > 0,
                "Consul issued a token through the auth method of the service");
    }

    private static Map<String, String> serviceEnvironment() {
        return Map.of(
                "MICROSERVICE_NAMESPACE", NAMESPACE,
                "MICROSERVICE_NAME", SERVICE.serviceName(),
                "CONSUL_URL", Cluster.CONSUL_IN_CLUSTER_URL,
                "CONSUL_AUTH_MODE", "kubernetes",
                "CONSUL_AUTH_METHOD", AUTH_METHOD,
                "CONSUL_AUTH_AUDIENCE", ProjectedToken.AUDIENCE);
    }
}
