package com.netcracker.cloud.core.configservermigration.consulacl;

import com.netcracker.cloud.core.configservermigration.stand.Cluster;
import com.netcracker.cloud.core.configservermigration.stand.Http;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.Cloud;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.EnableExtension;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.IntValue;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.PortForward;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.Value;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.netcracker.cloud.core.configservermigration.consulacl.ConsulAclStand.NAMESPACE;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * core-legacy-api in the Consul auth mode {@code k8s}: the bootstrap deletes the static token secret a {@code legacy}
 * deployment left, and the service logs in to Consul with its ServiceAccount through the {@code ConsulACL} of its
 * chart. With no static token mounted, a write the service completes was made with the token of that login.
 *
 * <p>The {@code ConsulACL} grants write access to {@code config/<namespace>/} only. The tests that check its scope log
 * in as the service's ServiceAccount themselves, because the API of the service writes nowhere else.
 */
@EnableExtension
@Tag("core-legacy-api-consul-acl")
class KubernetesModeIT {

    private static final String PROFILE = "k8s";

    @Cloud(namespace = @Value(Cluster.CORE_NAMESPACE))
    private KubernetesClient kubernetes;

    @PortForward(serviceName = @Value(Cluster.CONSUL_SERVER), port = @IntValue(8500),
            cloud = @Cloud(namespace = @Value(Cluster.CONSUL_NAMESPACE)))
    private URL consulUrl;

    private ConsulAclStand stand;
    private ConsulApi admin;

    @BeforeAll
    void deploy() {
        stand = new ConsulAclStand(kubernetes, consulUrl, true);
        admin = stand.consulAsAdmin();
        stand.uninstall();
        stand.createStaticTokenSecret();
        stand.deploy("k8s");
    }

    @AfterAll
    void close() {
        if (stand != null) {
            stand.close();
        }
    }

    /** Reconciled means the condition {@code Successful} holds for the current generation, with no object failed. */
    @Test
    void theOperatorAppliesTheConsulAclOfTheChart() {
        await("the operator reconciles ConsulACL " + NAMESPACE + "/" + ConsulAclStand.SERVICE)
                .atMost(Duration.ofMinutes(1))
                .pollInterval(Duration.ofSeconds(2))
                .until(() -> successfulGeneration(stand.consulAcl()) != null);

        GenericKubernetesResource consulAcl = stand.consulAcl();
        assertAll(
                () -> assertEquals(consulAcl.getMetadata().getGeneration(), successfulGeneration(consulAcl),
                        "generation of the Successful condition"),
                () -> assertFalse(String.valueOf(consulAcl.getAdditionalProperties().get("status")).contains("error:"),
                        "status reports no failed object: " + consulAcl.getAdditionalProperties().get("status")));
    }

    @Test
    void theBootstrapDeletesTheStaticTokenSecret() {
        assertNull(stand.staticTokenSecret(), "secret " + ConsulAclStand.STATIC_TOKEN_SECRET);
    }

    @Test
    void aPropertyWrittenThroughTheServiceIsStoredInConsul() {
        String path = "/" + ConsulAclStand.APPLICATION + "/" + PROFILE;
        Http.Response response = stand.coreLegacyApi().post(path, Map.of("acl.probe", "written in k8s"));

        assertEquals(201, response.status(), "POST " + path + " answered " + response.body());
        assertEquals("written in k8s", ConsulAclStand.storedValue(admin, PROFILE, "acl.probe"), "acl.probe in Consul");
    }

    @Test
    void theServiceAccountMayWriteUnderTheConfigPrefixOfItsNamespace() {
        String path = "/v1/kv/config/" + NAMESPACE + "/" + ConsulAclStand.APPLICATION + "/scope-probe";

        assertEquals(200, putAsServiceAccount(path, "inside"), "PUT " + path);
    }

    @Test
    void theServiceAccountMayNotWriteOutsideTheConfigPrefixOfItsNamespace() {
        String path = "/v1/kv/config/" + NAMESPACE + "-other/" + ConsulAclStand.APPLICATION + "/scope-probe";

        assertEquals(403, putAsServiceAccount(path, "outside"), "PUT " + path);
    }

    /** The status of a KV write with the token of a login as the service's ServiceAccount. */
    private int putAsServiceAccount(String path, String value) {
        return admin.withToken(stand.logInAsServiceAccount(admin)).put(path, value).status();
    }

    /** The generation the condition {@code Successful=True} was set for, or {@code null} while there is none. */
    @SuppressWarnings("unchecked")
    private static Long successfulGeneration(GenericKubernetesResource consulAcl) {
        if (consulAcl == null || !(consulAcl.getAdditionalProperties().get("status") instanceof Map<?, ?> status)
                || !(status.get("conditions") instanceof List<?> conditions)) {
            return null;
        }
        return ((List<Map<String, Object>>) conditions).stream()
                .filter(condition -> "Successful".equals(condition.get("type"))
                        && "True".equals(condition.get("status")))
                .map(condition -> condition.get("observedGeneration"))
                .filter(Objects::nonNull)
                .map(observed -> ((Number) observed).longValue())
                .findFirst()
                .orElse(null);
    }
}
