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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T4 of the design: the m2m way end to end. The service runs in the {@code m2m} mode, its stand-in for the customer
 * security library signs a JWT, and Consul validates it with a jwt auth method named after the namespace — the name
 * the m2m way logs in to.
 *
 * <p>Compatibility with a real Identity Provider is out of reach here: the token is ours, not the provider's. What
 * this covers is our code and the exchange itself, and the migration lane the fallback mode falls back to.
 */
@ExtendWith(SpringServiceM2MLoginIT.Dump.class)
@DisplayName("The Spring service logs in to Consul the old way and reads its properties")
class SpringServiceM2MLoginIT {

    private static final String NAMESPACE = "consul-login-test-m2m";

    private static final String POLICY = "consul-login-test-m2m-read";
    private static final String ROLE = "consul-login-test-m2m-reader";
    private static final String KV_PREFIX = "config/" + NAMESPACE + "/";
    private static final String MARKER_KEY = KV_PREFIX + "application/service.marker";
    private static final String MARKER_VALUE = "marker-read-the-old-way";

    private static KubernetesClient kubernetes;
    private static LocalPortForward consulPortForward;
    private static LocalPortForward servicePortForward;
    private static ConsulClient consul;
    private static String bindingRuleId;

    @BeforeAll
    static void prepareStand() {
        SigningKey signingKey = SigningKey.generate();

        kubernetes = Stand.newKubernetesClient();
        consulPortForward = Stand.forwardConsulPort(kubernetes);
        consul = new ConsulClient("http://localhost:" + consulPortForward.getLocalPort(),
                Stand.readBootstrapToken(kubernetes));

        consul.put("/v1/kv/" + MARKER_KEY, MARKER_VALUE).requireSuccess("seeding the marker key");
        ConsulAcl.createReadPolicy(consul, POLICY, KV_PREFIX);
        ConsulAcl.createRole(consul, ROLE, POLICY);
        ConsulAcl.createJwtAuthMethod(consul, NAMESPACE, signingKey.publicKeyPem(),
                SigningKey.ISSUER, SigningKey.AUDIENCE, null);
        bindingRuleId = ConsulAcl.createBindingRule(consul, NAMESPACE,
                "value.sub==\"" + Stand.SERVICE_NAME + "\"", ROLE);

        Stand.deployService(kubernetes, NAMESPACE, serviceEnvironment(signingKey), false);
        servicePortForward = Stand.forwardServicePort(kubernetes, NAMESPACE);
    }

    @Test
    @DisplayName("The service holds a token issued to its own JWT and serves the property it read")
    void serviceLogsInWithItsOwnJwt() {
        JsonNode status = Stand.awaitLoginStatus(servicePortForward);

        assertEquals("m2m", status.path("loginMode").asText(), "login mode");
        assertTrue(status.path("tokenPresent").asBoolean(), "the service holds a Consul token");
        assertEquals(MARKER_VALUE, status.path("consulMarker").asText(), "property read from Consul");
        assertTrue(ConsulAcl.issuedTokenCount(consul, NAMESPACE) > 0,
                "Consul issued a token through the auth method of the namespace");
    }

    @AfterAll
    static void cleanUpStand() {
        if (consul != null) {
            ConsulAcl.deleteIssuedTokens(consul, NAMESPACE);
            if (bindingRuleId != null) {
                consul.delete("/v1/acl/binding-rule/" + bindingRuleId);
            }
            consul.delete("/v1/acl/auth-method/" + NAMESPACE);
            ConsulAcl.deleteRole(consul, ROLE);
            ConsulAcl.deletePolicy(consul, POLICY);
            consul.delete("/v1/kv/" + KV_PREFIX + "?recurse=true");
        }
        Stand.tearDown(kubernetes, NAMESPACE, servicePortForward, consulPortForward);
    }

    /**
     * {@code NAMESPACE} is set next to {@code CLOUD_NAMESPACE} on purpose: the ConfigData phase accepts either, but
     * the autoconfiguration of the transport reads only {@code NAMESPACE}, and without it the context fails to start
     * after a login that already succeeded.
     */
    private static Map<String, String> serviceEnvironment(SigningKey signingKey) {
        return Map.of(
                "CLOUD_NAMESPACE", NAMESPACE,
                "NAMESPACE", NAMESPACE,
                "MICROSERVICE_NAME", Stand.SERVICE_NAME,
                "CONSUL_HOST", "consul-consul-server.consul",
                "CONSUL_LOGIN_MODE", "m2m",
                "CONSUL_LOGIN_M2M_PRIVATE_KEY", signingKey.privateKeyBase64(),
                "CONSUL_LOGIN_M2M_ISSUER", SigningKey.ISSUER,
                "CONSUL_LOGIN_M2M_AUDIENCE", SigningKey.AUDIENCE,
                "CONSUL_LOGIN_M2M_SUBJECT", Stand.SERVICE_NAME);
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
