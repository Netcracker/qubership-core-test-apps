package com.netcracker.cloud.core.consullogin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.ServiceAccount;
import io.fabric8.kubernetes.api.model.ServiceAccountBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.LocalPortForward;
import io.fabric8.kubernetes.client.dsl.ExecWatch;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.LifecycleMethodExecutionExceptionHandler;
import org.junit.jupiter.api.extension.TestWatcher;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static com.netcracker.cloud.core.consullogin.Stand.AUDIENCE;
import static com.netcracker.cloud.core.consullogin.Stand.CONSUL_IN_CLUSTER_URL;
import static com.netcracker.cloud.core.consullogin.Stand.TOKEN_MOUNT_PATH;
import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(ConsulKubernetesLoginIT.Dump.class)
@DisplayName("Consul Kubernetes auth method issues a token to a pod with a netcracker-audience token")
class ConsulKubernetesLoginIT {


    private static final String PROBE_NAMESPACE = "consul-login-probe";
    private static final String PROBE_SERVICE_ACCOUNT = "consul-login-probe";
    private static final String PROBE_POD = "consul-login-probe";
    private static final String PROBE_IMAGE = "curlimages/curl:8.11.0";

    private static final String AUTH_METHOD = "consul-login-probe";
    private static final String POLICY = "consul-login-probe-read";
    private static final String ROLE = "consul-login-probe-reader";
    private static final String DENY_POLICY = "consul-login-probe-deny-anonymous";
    private static final String ANONYMOUS_TOKEN_ID = "00000000-0000-0000-0000-000000000002";
    private static final String KV_PREFIX = "config/consul-login-itest/";
    private static final String KV_KEY = KV_PREFIX + "probe";
    private static final String KV_VALUE = "consul-kubernetes-login-works";

    private static final ObjectMapper JSON = new ObjectMapper();

    private static KubernetesClient kubernetes;
    private static LocalPortForward consulPortForward;
    private static ConsulClient consul;
    private static String bindingRuleId;

    @BeforeAll
    static void prepareStand() {
        kubernetes = Stand.newKubernetesClient();
        consulPortForward = Stand.forwardConsulPort(kubernetes);
        consul = new ConsulClient("http://localhost:" + consulPortForward.getLocalPort(),
                Stand.readBootstrapToken(kubernetes));

        consul.put("/v1/kv/" + KV_KEY, KV_VALUE).requireSuccess("seeding the probe key");
        createPolicy();
        createRole();
        createAuthMethod();
        bindingRuleId = createBindingRule();
        denyAnonymousAccess();
        createProbePod();
    }

    @Test
    @DisplayName("A pod logs in to Consul and reads the seeded key with the issued token")
    void podLogsInAndReadsSeededKey() {
        String script = """
                set -e
                bearer=$(cat %s/token)
                secret=$(curl -sS -X POST -d '{"AuthMethod":"%s","BearerToken":"'"$bearer"'"}' %s/v1/acl/login | tr ',' '\\n' | grep '"SecretID"' | cut -d'"' -f4)
                if [ -z "$secret" ]; then echo LOGIN_FAILED; exit 1; fi
                curl -sS -H "X-Consul-Token: $secret" %s/v1/kv/%s?raw
                """.formatted(TOKEN_MOUNT_PATH, AUTH_METHOD, CONSUL_IN_CLUSTER_URL, CONSUL_IN_CLUSTER_URL, KV_KEY);

        assertEquals(KV_VALUE, execInProbePod(script));
    }

    @Test
    @DisplayName("Without a token the probe prefix stays unreadable")
    void anonymousReadIsRefused() {
        String script = """
                curl -sS -o /dev/null -w '%%{http_code}' %s/v1/kv/%s?raw
                """.formatted(CONSUL_IN_CLUSTER_URL, KV_KEY);

        assertEquals("403", execInProbePod(script));
    }

    @AfterAll
    static void cleanUpStand() {
        if (consul != null) {
            restoreAnonymousAccess();
            deleteIssuedTokens();
            if (bindingRuleId != null) {
                consul.delete("/v1/acl/binding-rule/" + bindingRuleId);
            }
            consul.delete("/v1/acl/auth-method/" + AUTH_METHOD);
            deleteByName("/v1/acl/roles", "/v1/acl/role/", ROLE);
            deleteByName("/v1/acl/policies", "/v1/acl/policy/", POLICY);
            deleteByName("/v1/acl/policies", "/v1/acl/policy/", DENY_POLICY);
            consul.delete("/v1/kv/" + KV_KEY);
        }
        if (kubernetes != null) {
            kubernetes.namespaces().withName(PROBE_NAMESPACE).delete();
        }
        closeQuietly();
    }

                private static void createAuthMethod() {
        ObjectNode config = Stand.authMethodConfig(kubernetes, JSON.createObjectNode());
        ObjectNode body = JSON.createObjectNode()
                .put("Name", AUTH_METHOD)
                .put("Type", "kubernetes")
                .put("Description", "Consul login probe: Kubernetes auth method");
        body.set("Config", config);

        consul.put("/v1/acl/auth-method", body.toString()).requireSuccess("creating the auth method");
    }

    private static void createPolicy() {
        ObjectNode body = JSON.createObjectNode()
                .put("Name", POLICY)
                .put("Description", "Consul login probe: read access to the probe prefix")
                .put("Rules", "key_prefix \"" + KV_PREFIX + "\" { policy = \"read\" }");
        consul.put("/v1/acl/policy", body.toString()).requireSuccess("creating the policy");
    }

    private static void createRole() {
        ObjectNode policy = JSON.createObjectNode().put("Name", POLICY);
        ObjectNode body = JSON.createObjectNode()
                .put("Name", ROLE)
                .put("Description", "Consul login probe: role granting the probe policy");
        body.putArray("Policies").add(policy);
        consul.put("/v1/acl/role", body.toString()).requireSuccess("creating the role");
    }

    private static String createBindingRule() {
        ObjectNode body = JSON.createObjectNode()
                .put("AuthMethod", AUTH_METHOD)
                .put("Description", "Consul login probe: probe namespace to the probe role")
                .put("Selector", "serviceaccount.namespace==\"" + PROBE_NAMESPACE + "\"")
                .put("BindType", "role")
                .put("BindName", ROLE);
        ConsulClient.Response response = consul.put("/v1/acl/binding-rule", body.toString())
                .requireSuccess("creating the binding rule");
        return readJson(response.body()).path("ID").asText();
    }

    private static void denyAnonymousAccess() {
        ObjectNode body = JSON.createObjectNode()
                .put("Name", DENY_POLICY)
                .put("Description", "Consul login probe: the anonymous token cannot reach the probe prefix")
                .put("Rules", "key_prefix \"" + KV_PREFIX + "\" { policy = \"deny\" }");
        consul.put("/v1/acl/policy", body.toString()).requireSuccess("creating the deny policy");
        setDenyPolicyOnAnonymousToken(true);
    }

    private static void restoreAnonymousAccess() {
        setDenyPolicyOnAnonymousToken(false);
    }

    private static void setDenyPolicyOnAnonymousToken(boolean attached) {
        ConsulClient.Response current = consul.get("/v1/acl/token/" + ANONYMOUS_TOKEN_ID);
        if (!current.isSuccessful()) {
            return;
        }
        ObjectNode token = (ObjectNode) readJson(current.body());
        ArrayNode policies = JSON.createArrayNode();
        for (JsonNode policy : token.path("Policies")) {
            if (!DENY_POLICY.equals(policy.path("Name").asText())) {
                policies.add(policy);
            }
        }
        if (attached) {
            policies.add(JSON.createObjectNode().put("Name", DENY_POLICY));
        }
        token.set("Policies", policies);
        consul.put("/v1/acl/token/" + ANONYMOUS_TOKEN_ID, token.toString())
                .requireSuccess("updating the anonymous token");
    }

    private static void createProbePod() {
        Namespace namespace = new NamespaceBuilder()
                .withNewMetadata().withName(PROBE_NAMESPACE).endMetadata()
                .build();
        kubernetes.namespaces().resource(namespace).create();

        ServiceAccount serviceAccount = new ServiceAccountBuilder()
                .withNewMetadata().withName(PROBE_SERVICE_ACCOUNT).withNamespace(PROBE_NAMESPACE).endMetadata()
                .build();
        kubernetes.serviceAccounts().inNamespace(PROBE_NAMESPACE).resource(serviceAccount).create();

        Pod pod = new PodBuilder()
                .withNewMetadata().withName(PROBE_POD).withNamespace(PROBE_NAMESPACE).endMetadata()
                .withNewSpec()
                .withServiceAccountName(PROBE_SERVICE_ACCOUNT)
                .addNewContainer()
                .withName("probe")
                .withImage(PROBE_IMAGE)
                .withCommand("sleep", "infinity")
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
                .build();
        kubernetes.pods().inNamespace(PROBE_NAMESPACE).resource(pod).create();
        kubernetes.pods().inNamespace(PROBE_NAMESPACE).withName(PROBE_POD).waitUntilReady(3, TimeUnit.MINUTES);
    }

    private static String execInProbePod(String script) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        int exitCode;
        try (ExecWatch watch = kubernetes.pods().inNamespace(PROBE_NAMESPACE).withName(PROBE_POD)
                .writingOutput(out)
                .writingError(error)
                .exec("sh", "-c", script)) {
            exitCode = watch.exitCode().get(2, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("probe execution interrupted", e);
        } catch (Exception e) {
            throw new IllegalStateException("probe execution failed: " + e.getMessage(), e);
        }
        if (exitCode != 0) {
            throw new IllegalStateException("probe exited with code " + exitCode
                    + ", stderr: " + error.toString(StandardCharsets.UTF_8).trim());
        }
        return out.toString(StandardCharsets.UTF_8).trim();
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
        } catch (Exception e) {
            throw new IllegalStateException("cannot parse a Consul response as JSON", e);
        }
    }

    private static void closeQuietly() {
        if (consulPortForward != null) {
            try {
                consulPortForward.close();
            } catch (Exception ignored) {
            }
        }
        if (kubernetes != null) {
            kubernetes.close();
        }
    }

    static final class Dump implements TestWatcher, LifecycleMethodExecutionExceptionHandler {

        @Override
        public void testFailed(ExtensionContext context, Throwable cause) {
            StandDump.print(context.getDisplayName(), consul, kubernetes, PROBE_NAMESPACE);
        }

        @Override
        public void handleBeforeAllMethodExecutionException(ExtensionContext context, Throwable failure)
                throws Throwable {
            StandDump.print("stand preparation", consul, kubernetes, PROBE_NAMESPACE);
            throw failure;
        }
    }
}
