package com.netcracker.cloud.core.consullogin;

import com.fasterxml.jackson.databind.JsonNode;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.LocalPortForward;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.LifecycleMethodExecutionExceptionHandler;
import org.junit.jupiter.api.extension.TestWatcher;

import java.util.Map;

import static com.netcracker.cloud.core.consullogin.Stand.AUDIENCE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(SpringServiceKubernetesLoginIT.Dump.class)
@DisplayName("The Spring service logs in to Consul with its projected token and reads its properties")
class SpringServiceKubernetesLoginIT {

    private static final String NAMESPACE = "consul-login-test";

    private static final String AUTH_METHOD = "consul-login-test-service";
    private static final String POLICY = "consul-login-test-service-read";
    private static final String ROLE = "consul-login-test-service-reader";
    private static final String KV_PREFIX = "config/" + NAMESPACE + "/";
    private static final String MARKER_KEY = KV_PREFIX + "application/service.marker";
    private static final String MARKER_VALUE = "marker-read-with-the-issued-token";

    private static KubernetesClient kubernetes;
    private static LocalPortForward consulPortForward;
    private static LocalPortForward servicePortForward;
    private static ConsulClient consul;
    private static String bindingRuleId;

    @BeforeAll
    static void prepareStand() {
        kubernetes = Stand.newKubernetesClient();
        consulPortForward = Stand.forwardConsulPort(kubernetes);
        consul = new ConsulClient("http://localhost:" + consulPortForward.getLocalPort(),
                Stand.readBootstrapToken(kubernetes));

        consul.put("/v1/kv/" + MARKER_KEY, MARKER_VALUE).requireSuccess("seeding the marker key");
        ConsulAcl.createReadPolicy(consul, POLICY, KV_PREFIX);
        ConsulAcl.createRole(consul, ROLE, POLICY);
        ConsulAcl.createKubernetesAuthMethod(consul, kubernetes, AUTH_METHOD);
        bindingRuleId = ConsulAcl.createBindingRule(consul, AUTH_METHOD,
                "serviceaccount.namespace==\"" + NAMESPACE + "\"", ROLE);
        Stand.deployService(kubernetes, NAMESPACE, serviceEnvironment(), true);
        servicePortForward = Stand.forwardServicePort(kubernetes, NAMESPACE);
    }

    @Test
    @DisplayName("The service reports a Consul token of its own and the property it read")
    void serviceLogsInAndReadsItsProperties() {
        JsonNode status = Stand.awaitLoginStatus(servicePortForward);

        assertEquals("kubernetes", status.path("loginMode").asText(), "login mode");
        assertTrue(status.path("tokenPresent").asBoolean(), "the service holds a Consul token");
        assertEquals(MARKER_VALUE, status.path("consulMarker").asText(), "property read from Consul");
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
        Stand.tearDown(kubernetes, NAMESPACE, servicePortForward, consulPortForward);
    }

    private static Map<String, String> serviceEnvironment() {
        return Map.of(
                "CLOUD_NAMESPACE", NAMESPACE,
                "MICROSERVICE_NAME", Stand.SERVICE_NAME,
                "CONSUL_HOST", "consul-consul-server.consul",
                "CONSUL_LOGIN_MODE", "kubernetes",
                "CONSUL_LOGIN_AUTH_METHOD", AUTH_METHOD,
                "CONSUL_LOGIN_AUDIENCE", AUDIENCE);
    }

                                static final class Dump implements TestWatcher, LifecycleMethodExecutionExceptionHandler {

        @Override
        public void testFailed(ExtensionContext context, Throwable cause) {
            StandDump.print(context.getDisplayName(), consul, kubernetes, NAMESPACE);
        }

        @Override
        public void handleBeforeAllMethodExecutionException(ExtensionContext context, Throwable failure)
                throws Throwable {
            StandDump.print("stand preparation", consul, kubernetes, NAMESPACE);
            throw failure;
        }
    }
}
