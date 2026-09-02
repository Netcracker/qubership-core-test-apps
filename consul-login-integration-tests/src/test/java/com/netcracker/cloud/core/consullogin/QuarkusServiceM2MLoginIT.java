package com.netcracker.cloud.core.consullogin;

import com.fasterxml.jackson.databind.JsonNode;
import com.netcracker.cloud.core.consullogin.stand.Cluster;
import com.netcracker.cloud.core.consullogin.stand.ConsulAcl;
import com.netcracker.cloud.core.consullogin.stand.ConsulClient;
import com.netcracker.cloud.core.consullogin.stand.SigningKey;
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
 * The Quarkus service is configured to log in the way services did before the Kubernetes auth method existed: it asks
 * its security library for a token and presents that token to Consul, which validates it with a jwt auth method named
 * after the namespace of the service. It has to end up holding a Consul token of its own and serving the property the
 * test seeded.
 *
 * <p>The security library is stood in for by a signer of a key the test generates. Quarkus picks that stand-in out of
 * {@code ServiceLoader} by its priority rather than from a registry, which is what makes this worth running next to
 * the Spring scenario of the same way.
 */
@DisplayName("The Quarkus service logs in to Consul the old way and reads its properties")
class QuarkusServiceM2MLoginIT {

    private static final TestService SERVICE = TestService.QUARKUS;

    private static final String NAMESPACE = "consul-login-test-quarkus-m2m";

    private static final String POLICY = "consul-login-test-quarkus-m2m-read";
    private static final String ROLE = "consul-login-test-quarkus-m2m-reader";
    private static final String KV_PREFIX = "config/" + NAMESPACE + "/";
    private static final String MARKER_KEY = KV_PREFIX + "application/service.marker";
    private static final String MARKER_VALUE = "marker-read-by-quarkus-the-old-way";

    private static KubernetesClient kubernetes;
    private static LocalPortForward consulPortForward;
    private static LocalPortForward servicePortForward;
    private static ConsulClient consul;
    private static String bindingRuleId;

    @RegisterExtension
    static final StandDump standDump = StandDump.onFailure(() -> consul, () -> kubernetes, NAMESPACE);

    @BeforeAll
    static void prepareStand() {
        SigningKey signingKey = SigningKey.generate();

        kubernetes = Cluster.newClient();
        consulPortForward = Cluster.forwardConsulPort(kubernetes);
        consul = new ConsulClient("http://localhost:" + consulPortForward.getLocalPort(),
                Cluster.readBootstrapToken(kubernetes));

        consul.put("/v1/kv/" + MARKER_KEY, MARKER_VALUE).requireSuccess("seeding the marker key");
        ConsulAcl.createReadPolicy(consul, POLICY, KV_PREFIX);
        ConsulAcl.createRole(consul, ROLE, POLICY);
        ConsulAcl.createJwtAuthMethod(consul, NAMESPACE, signingKey.publicKeyPem(),
                SigningKey.ISSUER, SigningKey.AUDIENCE, null);
        bindingRuleId = ConsulAcl.createBindingRule(consul, NAMESPACE,
                "value.sub==\"" + SERVICE.serviceName() + "\"", ROLE);

        SERVICE.deploy(kubernetes, NAMESPACE, serviceEnvironment(signingKey), false);
        servicePortForward = SERVICE.forwardPort(kubernetes, NAMESPACE);
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
        Cluster.tearDown(kubernetes, NAMESPACE, servicePortForward, consulPortForward);
    }

    @Test
    @DisplayName("The service holds a token issued to its own JWT and serves the property it read")
    void serviceLogsInWithItsOwnJwt() {
        JsonNode status = TestService.awaitLoginStatus(servicePortForward);

        assertEquals("m2m", status.path("loginMode").asText(), "login mode");
        assertTrue(status.path("tokenPresent").asBoolean(), "the service holds a Consul token");
        assertEquals(MARKER_VALUE, status.path("consulMarker").asText(), "property read from Consul");
        assertTrue(ConsulAcl.issuedTokenCount(consul, NAMESPACE) > 0,
                "Consul issued a token through the auth method of the namespace");
    }

    private static Map<String, String> serviceEnvironment(SigningKey signingKey) {
        return Map.of(
                "CLOUD_NAMESPACE", NAMESPACE,
                "MICROSERVICE_NAME", SERVICE.serviceName(),
                "CONSUL_URL", Cluster.CONSUL_IN_CLUSTER_URL + "/",
                "CONSUL_LOGIN_MODE", "m2m",
                "CONSUL_LOGIN_M2M_PRIVATE_KEY", signingKey.privateKeyBase64(),
                "CONSUL_LOGIN_M2M_ISSUER", SigningKey.ISSUER,
                "CONSUL_LOGIN_M2M_AUDIENCE", SigningKey.AUDIENCE,
                "CONSUL_LOGIN_M2M_SUBJECT", SERVICE.serviceName());
    }
}
