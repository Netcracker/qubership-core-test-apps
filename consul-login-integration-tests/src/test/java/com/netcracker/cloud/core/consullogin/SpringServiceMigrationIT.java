package com.netcracker.cloud.core.consullogin;

import com.fasterxml.jackson.databind.JsonNode;
import com.netcracker.cloud.core.consullogin.stand.Cluster;
import com.netcracker.cloud.core.consullogin.stand.ConsulAcl;
import com.netcracker.cloud.core.consullogin.stand.ConsulClient;
import com.netcracker.cloud.core.consullogin.stand.ProjectedToken;
import com.netcracker.cloud.core.consullogin.stand.SigningKey;
import com.netcracker.cloud.core.consullogin.stand.StandDump;
import com.netcracker.cloud.core.consullogin.stand.TestService;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.LocalPortForward;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the migration between the two ways, in the order a service will meet it: a Spring service that already
 * carries the new library starts while the auth method of the kubernetes way does not exist yet. It has to serve its
 * properties anyway, over the old way, and to move to the kubernetes way on its own once the test creates that method,
 * on the next login it schedules and without a restart.
 *
 * <p>The way a pod took is read from Consul, which records the auth method every token came from, rather than from
 * the log of the service: the log line is written for a human, and parsing it in a test is brittle.
 */
@DisplayName("The Spring service migrates from the m2m way to the kubernetes way without a restart")
class SpringServiceMigrationIT {

    private static final TestService SERVICE = TestService.SPRING;

    private static final String NAMESPACE = "consul-login-test-migration";
    private static final String KUBERNETES_AUTH_METHOD = "consul-login-test-migration-kubernetes";

    private static final String POLICY = "consul-login-test-migration-read";
    private static final String ROLE = "consul-login-test-migration-reader";
    private static final String KV_PREFIX = "config/" + NAMESPACE + "/";
    private static final String MARKER_KEY = KV_PREFIX + "application/service.marker";
    private static final String MARKER_VALUE = "marker-read-across-the-migration";

    /**
     * A minute is the shortest lifetime Consul accepts, and the relogin it schedules is what carries the recheck of
     * the kubernetes way. The recheck interval is shorter still, so the first relogin after the auth method appears
     * already probes.
     */
    private static final String M2M_TOKEN_TTL = "1m";
    private static final String RECHECK_INTERVAL = "20s";
    private static final Duration MIGRATION_BUDGET = Duration.ofMinutes(4);

    private static KubernetesClient kubernetes;
    private static LocalPortForward consulPortForward;
    private static LocalPortForward servicePortForward;
    private static ConsulClient consul;
    private static String m2mBindingRuleId;
    private static String kubernetesBindingRuleId;

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
        ConsulAcl.createM2MAuthMethod(consul, NAMESPACE, signingKey.publicKeyPem(),
                SigningKey.ISSUER, SigningKey.AUDIENCE, M2M_TOKEN_TTL);
        m2mBindingRuleId = ConsulAcl.createBindingRule(consul, NAMESPACE,
                "value.sub==\"" + SERVICE.serviceName() + "\"", ROLE);

        SERVICE.deploy(kubernetes, NAMESPACE, serviceEnvironment(signingKey), true);
        servicePortForward = SERVICE.forwardPort(kubernetes, NAMESPACE);
    }

    @Test
    @DisplayName("It serves properties over m2m first and moves to the kubernetes way when its auth method appears")
    void serviceMovesToTheKubernetesWay() {
        JsonNode status = TestService.awaitLoginStatus(servicePortForward);

        assertEquals("kubernetes-with-m2m-fallback", status.path("loginMode").asText(), "login mode");
        assertEquals(MARKER_VALUE, status.path("consulMarker").asText(), "property read over the m2m way");
        assertTrue(ConsulAcl.issuedTokenCount(consul, NAMESPACE) > 0, "the m2m way carried the pod");
        assertEquals(0, ConsulAcl.issuedTokenCount(consul, KUBERNETES_AUTH_METHOD),
                "the kubernetes way has issued nothing while its auth method was missing");

        ConsulAcl.createKubernetesAuthMethod(consul, kubernetes, KUBERNETES_AUTH_METHOD);
        kubernetesBindingRuleId = ConsulAcl.createBindingRule(consul, KUBERNETES_AUTH_METHOD,
                "value.namespace==\"" + NAMESPACE + "\"", ROLE);

        Awaitility.await("the pod relogs in through the kubernetes auth method")
                .atMost(MIGRATION_BUDGET)
                .pollInterval(Duration.ofSeconds(5))
                .ignoreExceptions()
                .until(() -> ConsulAcl.issuedTokenCount(consul, KUBERNETES_AUTH_METHOD) > 0);

        assertEquals(MARKER_VALUE, TestService.awaitLoginStatus(servicePortForward).path("consulMarker").asText(),
                "the property still arrives after the migration");
    }

    @AfterAll
    static void cleanUpStand() {
        if (consul != null) {
            ConsulAcl.deleteIssuedTokens(consul, NAMESPACE);
            ConsulAcl.deleteIssuedTokens(consul, KUBERNETES_AUTH_METHOD);
            deleteBindingRule(kubernetesBindingRuleId);
            deleteBindingRule(m2mBindingRuleId);
            consul.delete("/v1/acl/auth-method/" + KUBERNETES_AUTH_METHOD);
            consul.delete("/v1/acl/auth-method/" + NAMESPACE);
            ConsulAcl.deleteRole(consul, ROLE);
            ConsulAcl.deletePolicy(consul, POLICY);
            consul.delete("/v1/kv/" + KV_PREFIX + "?recurse=true");
        }
        Cluster.tearDown(kubernetes, NAMESPACE, servicePortForward, consulPortForward);
    }

    private static void deleteBindingRule(String id) {
        if (id != null) {
            consul.delete("/v1/acl/binding-rule/" + id);
        }
    }

    private static Map<String, String> serviceEnvironment(SigningKey signingKey) {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("CLOUD_NAMESPACE", NAMESPACE);
        environment.put("NAMESPACE", NAMESPACE);
        environment.put("MICROSERVICE_NAME", SERVICE.serviceName());
        environment.put("CONSUL_HOST", "consul-consul-server.consul");
        environment.put("CONSUL_LOGIN_MODE", "kubernetes-with-m2m-fallback");
        environment.put("CONSUL_LOGIN_AUTH_METHOD", KUBERNETES_AUTH_METHOD);
        environment.put("CONSUL_LOGIN_AUDIENCE", ProjectedToken.AUDIENCE);
        environment.put("CONSUL_LOGIN_RECHECK", RECHECK_INTERVAL);
        environment.put("CONSUL_LOGIN_M2M_PRIVATE_KEY", signingKey.privateKeyBase64());
        environment.put("CONSUL_LOGIN_M2M_ISSUER", SigningKey.ISSUER);
        environment.put("CONSUL_LOGIN_M2M_AUDIENCE", SigningKey.AUDIENCE);
        environment.put("CONSUL_LOGIN_M2M_SUBJECT", SERVICE.serviceName());
        return environment;
    }
}
