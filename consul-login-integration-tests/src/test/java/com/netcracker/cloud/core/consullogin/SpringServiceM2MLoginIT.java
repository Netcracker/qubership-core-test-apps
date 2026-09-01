package com.netcracker.cloud.core.consullogin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

import java.io.IOException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
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

    private static final String ISSUER = "https://consul-login-integration-tests";
    private static final String AUDIENCE = "consul";

    private static final ObjectMapper JSON = new ObjectMapper();

    private static KubernetesClient kubernetes;
    private static LocalPortForward consulPortForward;
    private static LocalPortForward servicePortForward;
    private static ConsulClient consul;
    private static String bindingRuleId;

    @BeforeAll
    static void prepareStand() {
        KeyPair signingKey = generateSigningKey();

        kubernetes = Stand.newKubernetesClient();
        consulPortForward = Stand.forwardConsulPort(kubernetes);
        consul = new ConsulClient("http://localhost:" + consulPortForward.getLocalPort(),
                Stand.readBootstrapToken(kubernetes));

        consul.put("/v1/kv/" + MARKER_KEY, MARKER_VALUE).requireSuccess("seeding the marker key");
        createPolicy();
        createRole();
        createAuthMethod(signingKey);
        bindingRuleId = createBindingRule();

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
        assertTrue(issuedByTheAuthMethod(), "Consul issued a token through the auth method of the namespace");
    }

    @AfterAll
    static void cleanUpStand() {
        if (consul != null) {
            deleteIssuedTokens();
            if (bindingRuleId != null) {
                consul.delete("/v1/acl/binding-rule/" + bindingRuleId);
            }
            consul.delete("/v1/acl/auth-method/" + NAMESPACE);
            deleteByName("/v1/acl/roles", "/v1/acl/role/", ROLE);
            deleteByName("/v1/acl/policies", "/v1/acl/policy/", POLICY);
            consul.delete("/v1/kv/" + KV_PREFIX + "?recurse=true");
        }
        if (kubernetes != null) {
            kubernetes.namespaces().withName(NAMESPACE).delete();
        }
        closeQuietly(servicePortForward);
        closeQuietly(consulPortForward);
        if (kubernetes != null) {
            kubernetes.close();
        }
    }

    /**
     * The way the pod took is read from Consul rather than from the log of the service: the log line is written for
     * a human, and parsing it in a test is brittle.
     */
    private static boolean issuedByTheAuthMethod() {
        ConsulClient.Response response = consul.get("/v1/acl/tokens?authmethod=" + NAMESPACE)
                .requireSuccess("listing the tokens of the auth method");
        return Stand.readJson(response.body()).iterator().hasNext();
    }

    private static KeyPair generateSigningKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("RSA is required by the platform", e);
        }
    }

    /**
     * {@code NAMESPACE} is set next to {@code CLOUD_NAMESPACE} on purpose: the ConfigData phase accepts either, but
     * the autoconfiguration of the transport reads only {@code NAMESPACE}, and without it the context fails to start
     * after a login that already succeeded.
     */
    private static Map<String, String> serviceEnvironment(KeyPair signingKey) {
        return Map.of(
                "CLOUD_NAMESPACE", NAMESPACE,
                "NAMESPACE", NAMESPACE,
                "MICROSERVICE_NAME", Stand.SERVICE_NAME,
                "CONSUL_HOST", "consul-consul-server.consul",
                "CONSUL_LOGIN_MODE", "m2m",
                "CONSUL_LOGIN_M2M_PRIVATE_KEY",
                Base64.getEncoder().encodeToString(signingKey.getPrivate().getEncoded()),
                "CONSUL_LOGIN_M2M_ISSUER", ISSUER,
                "CONSUL_LOGIN_M2M_AUDIENCE", AUDIENCE,
                "CONSUL_LOGIN_M2M_SUBJECT", Stand.SERVICE_NAME);
    }

    private static void createPolicy() {
        ObjectNode body = JSON.createObjectNode()
                .put("Name", POLICY)
                .put("Description", "Consul login test service: read access to its own prefix, the m2m way")
                .put("Rules", "key_prefix \"" + KV_PREFIX + "\" { policy = \"read\" }");
        consul.put("/v1/acl/policy", body.toString()).requireSuccess("creating the policy");
    }

    private static void createRole() {
        ObjectNode body = JSON.createObjectNode()
                .put("Name", ROLE)
                .put("Description", "Consul login test service: role granting the m2m policy");
        body.putArray("Policies").add(JSON.createObjectNode().put("Name", POLICY));
        consul.put("/v1/acl/role", body.toString()).requireSuccess("creating the role");
    }

    /**
     * The m2m way logs in to an auth method named after the namespace of the microservice, so the name here is not
     * a choice.
     */
    private static void createAuthMethod(KeyPair signingKey) {
        ObjectNode config = JSON.createObjectNode()
                .put("BoundIssuer", ISSUER);
        config.putArray("JWTValidationPubKeys").add(publicKeyPem(signingKey));
        config.putArray("BoundAudiences").add(AUDIENCE);
        config.putObject("ClaimMappings").put("sub", "sub");

        ObjectNode body = JSON.createObjectNode()
                .put("Name", NAMESPACE)
                .put("Type", "jwt")
                .put("Description", "Consul login test service: jwt auth method of the m2m way");
        body.set("Config", config);
        consul.put("/v1/acl/auth-method", body.toString()).requireSuccess("creating the auth method");
    }

    private static String publicKeyPem(KeyPair signingKey) {
        String encoded = Base64.getMimeEncoder(64, "\n".getBytes())
                .encodeToString(signingKey.getPublic().getEncoded());
        return "-----BEGIN PUBLIC KEY-----\n" + encoded + "\n-----END PUBLIC KEY-----\n";
    }

    private static String createBindingRule() {
        ObjectNode body = JSON.createObjectNode()
                .put("AuthMethod", NAMESPACE)
                .put("Description", "Consul login test service: the signed subject to the m2m role")
                .put("Selector", "value.sub==\"" + Stand.SERVICE_NAME + "\"")
                .put("BindType", "role")
                .put("BindName", ROLE);
        ConsulClient.Response response = consul.put("/v1/acl/binding-rule", body.toString())
                .requireSuccess("creating the binding rule");
        return Stand.readJson(response.body()).path("ID").asText();
    }

    private static void deleteIssuedTokens() {
        ConsulClient.Response response = consul.get("/v1/acl/tokens?authmethod=" + NAMESPACE);
        if (!response.isSuccessful()) {
            return;
        }
        for (JsonNode token : Stand.readJson(response.body())) {
            consul.delete("/v1/acl/token/" + token.path("AccessorID").asText());
        }
    }

    private static void deleteByName(String listPath, String deletePath, String name) {
        ConsulClient.Response response = consul.get(listPath);
        if (!response.isSuccessful()) {
            return;
        }
        for (JsonNode item : Stand.readJson(response.body())) {
            if (name.equals(item.path("Name").asText())) {
                consul.delete(deletePath + item.path("ID").asText());
            }
        }
    }

    private static void closeQuietly(LocalPortForward portForward) {
        if (portForward == null) {
            return;
        }
        try {
            portForward.close();
        } catch (IOException e) {
            // the stand is being torn down anyway
        }
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
