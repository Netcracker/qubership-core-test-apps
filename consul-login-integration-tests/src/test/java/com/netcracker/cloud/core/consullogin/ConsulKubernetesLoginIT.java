package com.netcracker.cloud.core.consullogin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.netcracker.cloud.core.consullogin.stand.Cluster;
import com.netcracker.cloud.core.consullogin.stand.ConsulAcl;
import com.netcracker.cloud.core.consullogin.stand.ConsulClient;
import com.netcracker.cloud.core.consullogin.stand.ProjectedToken;
import com.netcracker.cloud.core.consullogin.stand.StandDump;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.ServiceAccount;
import io.fabric8.kubernetes.api.model.ServiceAccountBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.LocalPortForward;
import io.fabric8.kubernetes.client.dsl.ExecWatch;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Checks the stand rather than the library: a pod that carries a projected service account token of the netcracker
 * audience logs in through a Kubernetes auth method with plain curl, reads a key the test seeded with the token
 * Consul issued, and is refused that same key when it asks without a token.
 *
 * <p>Nothing here depends on the code under test, so a failure separates a broken stand from a broken library.
 */
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

    @RegisterExtension
    static final StandDump standDump = StandDump.onFailure(() -> consul, () -> kubernetes, PROBE_NAMESPACE);

    @BeforeAll
    static void prepareStand() {
        kubernetes = Cluster.newClient();
        consulPortForward = Cluster.forwardConsulPort(kubernetes);
        consul = new ConsulClient("http://localhost:" + consulPortForward.getLocalPort(),
                Cluster.readBootstrapToken(kubernetes));

        consul.put("/v1/kv/" + KV_KEY, KV_VALUE).requireSuccess("seeding the probe key");
        ConsulAcl.createReadPolicy(consul, POLICY, KV_PREFIX);
        ConsulAcl.createRole(consul, ROLE, POLICY);
        ConsulAcl.createKubernetesAuthMethod(consul, kubernetes, AUTH_METHOD);
        bindingRuleId = ConsulAcl.createBindingRule(consul, AUTH_METHOD,
                "serviceaccount.namespace==\"" + PROBE_NAMESPACE + "\"", ROLE);
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
                """.formatted(ProjectedToken.MOUNT_PATH, AUTH_METHOD,
                Cluster.CONSUL_IN_CLUSTER_URL, Cluster.CONSUL_IN_CLUSTER_URL, KV_KEY);

        assertEquals(KV_VALUE, execInProbePod(script));
    }

    @Test
    @DisplayName("Without a token the probe prefix stays unreadable")
    void anonymousReadIsRefused() {
        String script = """
                curl -sS -o /dev/null -w '%%{http_code}' %s/v1/kv/%s?raw
                """.formatted(Cluster.CONSUL_IN_CLUSTER_URL, KV_KEY);

        assertEquals("403", execInProbePod(script));
    }

    @AfterAll
    static void cleanUpStand() {
        if (consul != null) {
            restoreAnonymousAccess();
            ConsulAcl.deleteIssuedTokens(consul, AUTH_METHOD);
            if (bindingRuleId != null) {
                consul.delete("/v1/acl/binding-rule/" + bindingRuleId);
            }
            consul.delete("/v1/acl/auth-method/" + AUTH_METHOD);
            ConsulAcl.deleteRole(consul, ROLE);
            ConsulAcl.deletePolicy(consul, POLICY);
            ConsulAcl.deletePolicy(consul, DENY_POLICY);
            consul.delete("/v1/kv/" + KV_KEY);
        }
        if (kubernetes != null) {
            kubernetes.namespaces().withName(PROBE_NAMESPACE).delete();
        }
        closeQuietly();
    }

    private static void denyAnonymousAccess() {
        ConsulAcl.createDenyPolicy(consul, DENY_POLICY, KV_PREFIX);
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
        ObjectNode token = (ObjectNode) current.json();
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
                .addToVolumeMounts(ProjectedToken.mount())
                .endContainer()
                .addToVolumes(ProjectedToken.volume())
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
}
