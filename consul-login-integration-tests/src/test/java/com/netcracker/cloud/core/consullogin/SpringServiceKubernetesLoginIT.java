package com.netcracker.cloud.core.consullogin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.ServiceAccount;
import io.fabric8.kubernetes.api.model.ServiceAccountBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
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

import org.awaitility.Awaitility;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.netcracker.cloud.core.consullogin.Stand.AUDIENCE;
import static com.netcracker.cloud.core.consullogin.Stand.TOKEN_MOUNT_PATH;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(SpringServiceKubernetesLoginIT.Dump.class)
@DisplayName("The Spring service logs in to Consul with its projected token and reads its properties")
class SpringServiceKubernetesLoginIT {

    private static final String NAMESPACE = "consul-login-test";
    private static final String SERVICE = "consul-login-test-service-spring";
    private static final String IMAGE = System.getProperty("consul.login.test.service.image",
            "ghcr.io/netcracker/qubership-core-consul-login-test-service-spring"
                    + ":feat-consul-login-integration-tests-snapshot");

    private static final String AUTH_METHOD = "consul-login-test-service";
    private static final String POLICY = "consul-login-test-service-read";
    private static final String ROLE = "consul-login-test-service-reader";
    private static final String KV_PREFIX = "config/" + NAMESPACE + "/";
    private static final String MARKER_KEY = KV_PREFIX + "application/service.marker";
    private static final String MARKER_VALUE = "marker-read-with-the-issued-token";

    private static final ObjectMapper JSON = new ObjectMapper();

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
        createPolicy();
        createRole();
        createAuthMethod();
        bindingRuleId = createBindingRule();
        deployService();
        servicePortForward = forwardServicePort();
    }

    @Test
    @DisplayName("The service reports a Consul token of its own and the property it read")
    void serviceLogsInAndReadsItsProperties() {
        JsonNode status = awaitLoginStatus();

        assertEquals("kubernetes", status.path("loginMode").asText(), "login mode");
        assertTrue(status.path("tokenPresent").asBoolean(), "the service holds a Consul token");
        assertEquals(MARKER_VALUE, status.path("consulMarker").asText(), "property read from Consul");
    }

    @AfterAll
    static void cleanUpStand() {
        if (consul != null) {
            deleteIssuedTokens();
            if (bindingRuleId != null) {
                consul.delete("/v1/acl/binding-rule/" + bindingRuleId);
            }
            consul.delete("/v1/acl/auth-method/" + AUTH_METHOD);
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
     * The container is running long before Spring is listening, and the boot itself includes the Consul login,
     * so the status is polled instead of being read once.
     */
    private static JsonNode awaitLoginStatus() {
        ConsulClient service = new ConsulClient("http://localhost:" + servicePortForward.getLocalPort(), null);
        return Awaitility.await("the service answers on /login-status")
                .atMost(Duration.ofMinutes(3))
                .pollInterval(Duration.ofSeconds(3))
                .ignoreExceptions()
                .until(() -> {
                    ConsulClient.Response response = service.get("/login-status");
                    return response.isSuccessful() ? readJson(response.body()) : null;
                }, status -> status != null);
    }

    private static void createPolicy() {
        ObjectNode body = JSON.createObjectNode()
                .put("Name", POLICY)
                .put("Description", "Consul login test service: read access to its own prefix")
                .put("Rules", "key_prefix \"" + KV_PREFIX + "\" { policy = \"read\" }");
        consul.put("/v1/acl/policy", body.toString()).requireSuccess("creating the policy");
    }

    private static void createRole() {
        ObjectNode body = JSON.createObjectNode()
                .put("Name", ROLE)
                .put("Description", "Consul login test service: role granting the service policy");
        body.putArray("Policies").add(JSON.createObjectNode().put("Name", POLICY));
        consul.put("/v1/acl/role", body.toString()).requireSuccess("creating the role");
    }

    private static void createAuthMethod() {
        ObjectNode body = JSON.createObjectNode()
                .put("Name", AUTH_METHOD)
                .put("Type", "kubernetes")
                .put("Description", "Consul login test service: Kubernetes auth method");
        body.set("Config", Stand.authMethodConfig(kubernetes, JSON.createObjectNode()));
        consul.put("/v1/acl/auth-method", body.toString()).requireSuccess("creating the auth method");
    }

    private static String createBindingRule() {
        ObjectNode body = JSON.createObjectNode()
                .put("AuthMethod", AUTH_METHOD)
                .put("Description", "Consul login test service: service namespace to the service role")
                .put("Selector", "serviceaccount.namespace==\"" + NAMESPACE + "\"")
                .put("BindType", "role")
                .put("BindName", ROLE);
        ConsulClient.Response response = consul.put("/v1/acl/binding-rule", body.toString())
                .requireSuccess("creating the binding rule");
        return readJson(response.body()).path("ID").asText();
    }

    private static void deployService() {
        Namespace namespace = new NamespaceBuilder()
                .withNewMetadata().withName(NAMESPACE).endMetadata()
                .build();
        kubernetes.namespaces().resource(namespace).create();

        ServiceAccount serviceAccount = new ServiceAccountBuilder()
                .withNewMetadata().withName(SERVICE).withNamespace(NAMESPACE).endMetadata()
                .build();
        kubernetes.serviceAccounts().inNamespace(NAMESPACE).resource(serviceAccount).create();

        Map<String, String> labels = Map.of("app", SERVICE);
        Deployment deployment = new DeploymentBuilder()
                .withNewMetadata().withName(SERVICE).withNamespace(NAMESPACE).withLabels(labels).endMetadata()
                .withNewSpec()
                .withReplicas(1)
                .withNewSelector().withMatchLabels(labels).endSelector()
                .withNewTemplate()
                .withNewMetadata().withLabels(labels).endMetadata()
                .withNewSpec()
                .withServiceAccountName(SERVICE)
                .addNewContainer()
                .withName(SERVICE)
                .withImage(IMAGE)
                .addNewPort().withContainerPort(8080).endPort()
                .addNewEnv().withName("CLOUD_NAMESPACE").withValue(NAMESPACE).endEnv()
                .addNewEnv().withName("MICROSERVICE_NAME").withValue(SERVICE).endEnv()
                .addNewEnv().withName("CONSUL_HOST").withValue("consul-consul-server.consul").endEnv()
                .addNewEnv().withName("CONSUL_LOGIN_MODE").withValue("kubernetes").endEnv()
                .addNewEnv().withName("CONSUL_LOGIN_AUTH_METHOD").withValue(AUTH_METHOD).endEnv()
                .addNewEnv().withName("CONSUL_LOGIN_AUDIENCE").withValue(AUDIENCE).endEnv()
                .addNewVolumeMount()
                .withName("netcracker-token")
                .withMountPath(TOKEN_MOUNT_PATH)
                .withReadOnly(true)
                .endVolumeMount()
                .endContainer()
                .addNewVolume()
                .withName("netcracker-token")
                .withNewProjected()
                .addNewSource()
                .withNewServiceAccountToken()
                .withAudience(AUDIENCE)
                .withExpirationSeconds(3600L)
                .withPath("token")
                .endServiceAccountToken()
                .endSource()
                .endProjected()
                .endVolume()
                .endSpec()
                .endTemplate()
                .endSpec()
                .build();
        kubernetes.apps().deployments().inNamespace(NAMESPACE).resource(deployment).create();
        kubernetes.apps().deployments().inNamespace(NAMESPACE).withName(SERVICE)
                .waitUntilReady(5, TimeUnit.MINUTES);
    }

    private static LocalPortForward forwardServicePort() {
        Pod pod = kubernetes.pods().inNamespace(NAMESPACE).withLabel("app", SERVICE).list().getItems().stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no pod of " + SERVICE + " in namespace " + NAMESPACE));
        return kubernetes.pods().inNamespace(NAMESPACE).withName(pod.getMetadata().getName()).portForward(8080);
    }

    private static void deleteIssuedTokens() {
        ConsulClient.Response response = consul.get("/v1/acl/tokens?authmethod=" + AUTH_METHOD);
        if (!response.isSuccessful()) {
            return;
        }
        for (JsonNode token : readJson(response.body())) {
            consul.delete("/v1/acl/token/" + token.path("AccessorID").asText());
        }
    }

    private static void deleteByName(String listPath, String deletePath, String name) {
        ConsulClient.Response response = consul.get(listPath);
        if (!response.isSuccessful()) {
            return;
        }
        for (JsonNode item : readJson(response.body())) {
            if (name.equals(item.path("Name").asText())) {
                consul.delete(deletePath + item.path("ID").asText());
            }
        }
    }

    private static JsonNode readJson(String body) {
        try {
            return JSON.readTree(body);
        } catch (IOException e) {
            throw new IllegalStateException("unexpected response body", e);
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
