package com.netcracker.cloud.core.consullogin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.ServiceAccount;
import io.fabric8.kubernetes.api.model.ServiceAccountBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.LocalPortForward;
import org.awaitility.Awaitility;

import java.io.IOException;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

final class Stand {

    static final String CONSUL_NAMESPACE = "consul";
    static final String CONSUL_SERVER_POD_PREFIX = "consul-consul-server";
    static final String CONSUL_IN_CLUSTER_URL = "http://consul-consul-server.consul.svc:8500";
    static final String AUDIENCE = "netcracker";
    static final String TOKEN_MOUNT_PATH = "/var/run/secrets/tokens/netcracker";
    static final String SERVICE_NAME = "consul-login-test-service-spring";
    static final String SERVICE_IMAGE = System.getProperty("consul.login.test.service.image",
            "ghcr.io/netcracker/qubership-core-consul-login-test-service-spring"
                    + ":feat-consul-login-integration-tests-snapshot");

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TOKEN_VOLUME = "netcracker-token";

    private Stand() {
    }

    static KubernetesClient newKubernetesClient() {
        Config config = Config.autoConfigure(null);
        config.setTrustCerts(true);
        config.setDisableHostnameVerification(true);
        String master = System.getProperty("kubernetes.master");
        if (master != null && !master.isBlank()) {
            config.setMasterUrl(master);
        }
        return new KubernetesClientBuilder().withConfig(config).build();
    }

    static LocalPortForward forwardConsulPort(KubernetesClient kubernetes) {
        Pod server = kubernetes.pods().inNamespace(CONSUL_NAMESPACE).list().getItems().stream()
                .filter(pod -> pod.getMetadata().getName().startsWith(CONSUL_SERVER_POD_PREFIX))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "no pod with prefix " + CONSUL_SERVER_POD_PREFIX + " in namespace " + CONSUL_NAMESPACE));
        return kubernetes.pods()
                .inNamespace(CONSUL_NAMESPACE)
                .withName(server.getMetadata().getName())
                .portForward(8500);
    }

    static String readBootstrapToken(KubernetesClient kubernetes) {
        Secret secret = findSecret(kubernetes,
                candidate -> candidate.getMetadata().getName().endsWith("bootstrap-acl-token"),
                "no bootstrap ACL token secret in namespace " + CONSUL_NAMESPACE);
        return decode(secret, "token");
    }

    static ObjectNode authMethodConfig(KubernetesClient kubernetes, ObjectNode config) {
        Secret reviewer = findSecret(kubernetes,
                candidate -> candidate.getMetadata().getName().endsWith("auth-method")
                        && "kubernetes.io/service-account-token".equals(candidate.getType()),
                "no auth method reviewer secret in namespace " + CONSUL_NAMESPACE);
        return config
                .put("Host", "https://kubernetes.default.svc")
                .put("CACert", decode(reviewer, "ca.crt"))
                .put("ServiceAccountJWT", decode(reviewer, "token"));
    }

    /**
     * Deploys the test service in a namespace of its own. The projected token is mounted only for the ways that read
     * it, so a scenario that does not need it also proves it is not needed.
     */
    static void deployService(KubernetesClient kubernetes, String namespace, Map<String, String> environment,
                              boolean withProjectedToken) {
        Namespace target = new NamespaceBuilder()
                .withNewMetadata().withName(namespace).endMetadata()
                .build();
        kubernetes.namespaces().resource(target).create();

        ServiceAccount serviceAccount = new ServiceAccountBuilder()
                .withNewMetadata().withName(SERVICE_NAME).withNamespace(namespace).endMetadata()
                .build();
        kubernetes.serviceAccounts().inNamespace(namespace).resource(serviceAccount).create();

        Map<String, String> labels = Map.of("app", SERVICE_NAME);
        DeploymentBuilder builder = new DeploymentBuilder()
                .withNewMetadata().withName(SERVICE_NAME).withNamespace(namespace).withLabels(labels).endMetadata()
                .withNewSpec()
                .withReplicas(1)
                .withNewSelector().withMatchLabels(labels).endSelector()
                .withNewTemplate()
                .withNewMetadata().withLabels(labels).endMetadata()
                .withNewSpec()
                .withServiceAccountName(SERVICE_NAME)
                .addNewContainer()
                .withName(SERVICE_NAME)
                .withImage(SERVICE_IMAGE)
                .addNewPort().withContainerPort(8080).endPort()
                .endContainer()
                .endSpec()
                .endTemplate()
                .endSpec();
        environment.forEach((name, value) -> builder
                .editSpec().editTemplate().editSpec().editFirstContainer()
                .addNewEnv().withName(name).withValue(value).endEnv()
                .endContainer().endSpec().endTemplate().endSpec());
        if (withProjectedToken) {
            builder.editSpec().editTemplate().editSpec()
                    .editFirstContainer()
                    .addNewVolumeMount()
                    .withName(TOKEN_VOLUME).withMountPath(TOKEN_MOUNT_PATH).withReadOnly(true)
                    .endVolumeMount()
                    .endContainer()
                    .addNewVolume()
                    .withName(TOKEN_VOLUME)
                    .withNewProjected()
                    .addNewSource()
                    .withNewServiceAccountToken()
                    .withAudience(AUDIENCE).withExpirationSeconds(3600L).withPath("token")
                    .endServiceAccountToken()
                    .endSource()
                    .endProjected()
                    .endVolume()
                    .endSpec().endTemplate().endSpec();
        }
        Deployment deployment = builder.build();
        kubernetes.apps().deployments().inNamespace(namespace).resource(deployment).create();
        kubernetes.apps().deployments().inNamespace(namespace).withName(SERVICE_NAME)
                .waitUntilReady(5, TimeUnit.MINUTES);
    }

    static LocalPortForward forwardServicePort(KubernetesClient kubernetes, String namespace) {
        Pod pod = kubernetes.pods().inNamespace(namespace).withLabel("app", SERVICE_NAME).list().getItems().stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "no pod of " + SERVICE_NAME + " in namespace " + namespace));
        return kubernetes.pods().inNamespace(namespace).withName(pod.getMetadata().getName()).portForward(8080);
    }

    /**
     * The container is running long before the application is listening, and the boot itself includes the Consul
     * login, so the status is polled instead of being read once.
     */
    static JsonNode awaitLoginStatus(LocalPortForward portForward) {
        ConsulClient service = new ConsulClient("http://localhost:" + portForward.getLocalPort(), null);
        return Awaitility.await("the service answers on /login-status")
                .atMost(Duration.ofMinutes(3))
                .pollInterval(Duration.ofSeconds(3))
                .ignoreExceptions()
                .until(() -> {
                    ConsulClient.Response response = service.get("/login-status");
                    return response.isSuccessful() ? readJson(response.body()) : null;
                }, status -> status != null);
    }

    static JsonNode readJson(String body) {
        try {
            return JSON.readTree(body);
        } catch (IOException e) {
            throw new IllegalStateException("unexpected response body", e);
        }
    }

    /** Removes what a scenario left in the cluster and closes what it opened, in an order that survives failures. */
    static void tearDown(KubernetesClient kubernetes, String namespace, LocalPortForward... portForwards) {
        if (kubernetes != null && namespace != null) {
            kubernetes.namespaces().withName(namespace).delete();
        }
        for (LocalPortForward portForward : portForwards) {
            if (portForward == null) {
                continue;
            }
            try {
                portForward.close();
            } catch (IOException e) {
                // the stand is being torn down anyway
            }
        }
        if (kubernetes != null) {
            kubernetes.close();
        }
    }

    static Secret findSecret(KubernetesClient kubernetes, Predicate<Secret> matches, String notFoundMessage) {
        List<Secret> secrets = kubernetes.secrets().inNamespace(CONSUL_NAMESPACE).list().getItems();
        return secrets.stream()
                .filter(matches)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(notFoundMessage));
    }

    static String decode(Secret secret, String key) {
        String value = secret.getData().get(key);
        if (value == null) {
            throw new IllegalStateException(
                    "key " + key + " is missing from secret " + secret.getMetadata().getName());
        }
        return new String(Base64.getDecoder().decode(value), java.nio.charset.StandardCharsets.UTF_8);
    }
}
