package com.netcracker.cloud.core.configservermigration.consulacl;

import com.netcracker.cloud.core.configservermigration.stand.Cluster;
import com.netcracker.cloud.core.configservermigration.stand.Http;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.authentication.TokenRequestBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.LocalPortForward;
import io.fabric8.kubernetes.client.dsl.NonDeletingOperation;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.awaitility.Awaitility.await;

/**
 * The stand of the core-legacy-api Consul ACL tests: Consul with its dependencies installed by cloud-core-local-dev,
 * and core-legacy-api with the core-bootstrap pre-deploy job, which each test class installs in the Consul auth mode
 * it tests.
 *
 * <p>A test class installs core-legacy-api from scratch, so the pod reads its token and the bootstrap decides on the
 * {@value #STATIC_TOKEN_SECRET} secret in the mode of that class, whatever an earlier class left behind.
 */
final class ConsulAclStand implements AutoCloseable {

    static final String NAMESPACE = Cluster.CORE_NAMESPACE;
    static final String SERVICE = "core-legacy-api";
    static final String AUTH_METHOD = "applications-k8s-m2m";
    static final String AUDIENCE = "netcracker";
    static final String STATIC_TOKEN_SECRET = "config-server-consul-token";
    /** The application the tests write under, which no other suite of this module reads. */
    static final String APPLICATION = "consul-acl-it";

    private static final String CONSUL_NAMESPACE = Cluster.CONSUL_NAMESPACE;
    private static final String ACL_OPERATOR = "consul-acl-configurator-operator";
    private static final String BOOTSTRAP_TOKEN_SECRET = "consul-bootstrap-acl-token";
    private static final Duration ROLLOUT = Duration.ofMinutes(3);

    private final KubernetesClient kubernetes;
    private final URL consulUrl;
    private final boolean consulAcls;
    private LocalPortForward coreLegacyApiPortForward;

    /**
     * @param consulAcls whether Consul runs with ACLs and its ACL operator; with them, every deployment runs the
     *                   bootstrap with the Consul bootstrap ACL token as its admin token
     */
    ConsulAclStand(KubernetesClient kubernetes, URL consulUrl, boolean consulAcls) {
        this.kubernetes = kubernetes;
        this.consulUrl = consulUrl;
        this.consulAcls = consulAcls;
    }

    /** Uninstalls core-legacy-api and waits until its pods and its {@code ConsulACL} are gone. */
    void uninstall() {
        closePortForward();
        LocalDev.make("uninstall-core-legacy-api");
        await("pods and ConsulACL of " + SERVICE + " in " + NAMESPACE + " are deleted")
                .atMost(ROLLOUT)
                .pollInterval(Duration.ofSeconds(2))
                .until(() -> pods().isEmpty() && consulAcl() == null);
    }

    /** Runs the bootstrap where Consul has ACLs, then installs core-legacy-api and waits for its rollout. */
    void deploy(String consulAuthMode) {
        closePortForward();
        LocalDev.make(deployArguments(consulAuthMode));
    }

    /** {@link #deploy(String)} without waiting for it; {@link LocalDev#awaitSuccess(Process)} waits. */
    Process startDeploy(String consulAuthMode) {
        closePortForward();
        return LocalDev.start(deployArguments(consulAuthMode));
    }

    private String[] deployArguments(String consulAuthMode) {
        List<String> arguments = new ArrayList<>();
        if (consulAcls) {
            arguments.add("deploy-cloud-core-configuration");
        }
        arguments.addAll(List.of("deploy-core-legacy-api",
                "INSTALL_CONFIG_SERVER=false",
                "INSTALL_CORE_LEGACY_API=true",
                "CONSUL_ACLS=" + consulAcls,
                "CORE_CONFIG_CONSUL_ENABLED=" + consulAcls,
                "CONSUL_AUTH_MODE=" + consulAuthMode));
        return arguments.toArray(String[]::new);
    }

    /** The API of the ready core-legacy-api pod, through a port forward opened on the first call after a deploy. */
    Http coreLegacyApi() {
        if (coreLegacyApiPortForward == null) {
            Pod pod = readyPod();
            coreLegacyApiPortForward = kubernetes.pods().inNamespace(NAMESPACE)
                    .withName(pod.getMetadata().getName()).portForward(8080);
        }
        return new Http(url("http://localhost:" + coreLegacyApiPortForward.getLocalPort() + "/"));
    }

    /** Consul with the bootstrap ACL token, which may read and write anything. */
    ConsulApi consulAsAdmin() {
        Secret secret = kubernetes.secrets().inNamespace(CONSUL_NAMESPACE).withName(BOOTSTRAP_TOKEN_SECRET).get();
        if (secret == null) {
            throw new IllegalStateException("no secret " + BOOTSTRAP_TOKEN_SECRET + " in namespace "
                    + CONSUL_NAMESPACE + ": Consul was installed without ACLs");
        }
        return new ConsulApi(consulUrl, decode(secret, "token"));
    }

    /** Consul with no token, as a client of a Consul without ACLs. */
    ConsulApi consulWithoutToken() {
        return new ConsulApi(consulUrl, null);
    }

    /** The value Consul stores for a property of {@link #APPLICATION}, or {@code null} if it has none. */
    static String storedValue(ConsulApi consul, String profile, String key) {
        String path = "/v1/kv/config/" + NAMESPACE + "/" + APPLICATION + "," + profile + "/" + key.replace('.', '/')
                + "?raw";
        ConsulApi.Response response = consul.get(path);
        return response.status() == 404 ? null : response.requireSuccess().body();
    }

    /**
     * Creates the static token secret, as a deployment in the mode {@code legacy} leaves it, for a test that checks
     * that the bootstrap deletes it.
     */
    void createStaticTokenSecret() {
        Secret secret = new SecretBuilder()
                .withNewMetadata().withName(STATIC_TOKEN_SECRET).withNamespace(NAMESPACE).endMetadata()
                .addToStringData("token", "left by a legacy deployment")
                .build();
        kubernetes.secrets().inNamespace(NAMESPACE).resource(secret).createOr(NonDeletingOperation::update);
    }

    /** The secret core-bootstrap issues the static token in, or {@code null} if there is none. */
    Secret staticTokenSecret() {
        return kubernetes.secrets().inNamespace(NAMESPACE).withName(STATIC_TOKEN_SECRET).get();
    }

    /** The static token core-bootstrap issued, read from {@link #staticTokenSecret()}. */
    String staticToken() {
        Secret secret = staticTokenSecret();
        if (secret == null) {
            throw new IllegalStateException("no secret " + STATIC_TOKEN_SECRET + " in namespace " + NAMESPACE);
        }
        return decode(secret, "token");
    }

    /** Whether a core-legacy-api container has started, so that the process runs or has run. */
    boolean containerStarted() {
        return pods().stream()
                .filter(pod -> pod.getStatus() != null && pod.getStatus().getContainerStatuses() != null)
                .flatMap(pod -> pod.getStatus().getContainerStatuses().stream())
                .anyMatch(status -> status.getState().getRunning() != null || status.getRestartCount() > 0);
    }

    /**
     * Logs in to Consul as the core-legacy-api ServiceAccount, with a token the API server issues for the audience
     * the pod presents, and returns the secret of the Consul token.
     */
    String logInAsServiceAccount(ConsulApi consul) {
        String bearerToken = kubernetes.serviceAccounts().inNamespace(NAMESPACE).withName(SERVICE)
                .tokenRequest(new TokenRequestBuilder()
                        .withNewSpec().withAudiences(AUDIENCE).withExpirationSeconds(600L).endSpec()
                        .build())
                .getStatus().getToken();
        String body = "{\"AuthMethod\":\"" + AUTH_METHOD + "\",\"BearerToken\":\"" + bearerToken + "\"}";
        return consul.post("/v1/acl/login", body).requireSuccess().json().path("SecretID").asText();
    }

    /** The {@code ConsulACL} of the chart, or {@code null} if there is none. */
    GenericKubernetesResource consulAcl() {
        return kubernetes.genericKubernetesResources("netcracker.com/v1", "ConsulACL")
                .inNamespace(NAMESPACE).withName(SERVICE).get();
    }

    Deployment deployment() {
        return kubernetes.apps().deployments().inNamespace(NAMESPACE).withName(SERVICE).get();
    }

    /** Scales the Consul ACL operator and waits until it runs that many ready pods. */
    void scaleAclOperator(int replicas) {
        kubernetes.apps().deployments().inNamespace(CONSUL_NAMESPACE).withName(ACL_OPERATOR).scale(replicas);
        await(ACL_OPERATOR + " runs " + replicas + " ready pods")
                .atMost(ROLLOUT)
                .pollInterval(Duration.ofSeconds(2))
                .until(() -> {
                    Deployment operator = kubernetes.apps().deployments().inNamespace(CONSUL_NAMESPACE)
                            .withName(ACL_OPERATOR).get();
                    Integer ready = operator.getStatus().getReadyReplicas();
                    return Objects.requireNonNullElse(ready, 0) == replicas
                            && kubernetes.pods().inNamespace(CONSUL_NAMESPACE)
                            .withLabels(operator.getSpec().getSelector().getMatchLabels()).list().getItems().size()
                            == replicas;
                });
    }

    @Override
    public void close() {
        closePortForward();
    }

    private Pod readyPod() {
        return pods().stream()
                .filter(pod -> pod.getMetadata().getDeletionTimestamp() == null)
                .filter(pod -> pod.getStatus().getConditions().stream()
                        .anyMatch(condition -> "Ready".equals(condition.getType())
                                && "True".equals(condition.getStatus())))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no ready " + SERVICE + " pod in " + NAMESPACE));
    }

    private List<Pod> pods() {
        return kubernetes.pods().inNamespace(NAMESPACE).withLabels(Map.of("name", SERVICE)).list().getItems();
    }

    private void closePortForward() {
        if (coreLegacyApiPortForward == null) {
            return;
        }
        try {
            coreLegacyApiPortForward.close();
        } catch (IOException e) {
            // the pod behind it is being replaced anyway
        }
        coreLegacyApiPortForward = null;
    }

    private static String decode(Secret secret, String key) {
        String value = secret.getData() == null ? null : secret.getData().get(key);
        if (value == null) {
            throw new IllegalStateException("no key " + key + " in secret " + secret.getMetadata().getName());
        }
        return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private static URL url(String spec) {
        try {
            return URI.create(spec).toURL();
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException(spec, e);
        }
    }
}
