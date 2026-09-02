package com.netcracker.cloud.core.consullogin.stand;

import com.fasterxml.jackson.databind.JsonNode;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.ServiceAccount;
import io.fabric8.kubernetes.api.model.ServiceAccountBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.LocalPortForward;
import org.awaitility.Awaitility;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The service under test on the stand: a namespace, a service account and a deployment per scenario, and the status
 * endpoint a scenario reads the result of a login through.
 *
 * <p>These objects are created here rather than declared in a helm chart because a scenario decides them while it
 * runs: its own namespace, its own environment for the login way under test, a signing key generated for the run, and
 * the projected token only where the way reads it. One scenario also has to bring the pod up before the ACL objects it
 * later moves to exist, which no install step outside the test can arrange.
 */
public final class TestService {

    public static final String NAME = "consul-login-test-service-spring";

    /**
     * The image built from {@code consul-login-test-service-spring} in this repository. Override it with
     * {@code -Dconsul.login.test.service.image} to run the scenarios against a build of your own.
     */
    public static final String IMAGE = System.getProperty("consul.login.test.service.image",
            "ghcr.io/netcracker/qubership-core-consul-login-test-service-spring"
                    + ":feat-consul-login-integration-tests-snapshot");

    private static final int PORT = 8080;

    private TestService() {
    }

    /**
     * Deploys the service in a namespace of its own and waits for the deployment to become ready. The projected token
     * is mounted only for the ways that read it, so a scenario that does not need it also proves it is not needed.
     */
    public static void deploy(KubernetesClient kubernetes, String namespace, Map<String, String> environment,
                              boolean withProjectedToken) {
        Namespace target = new NamespaceBuilder()
                .withNewMetadata().withName(namespace).endMetadata()
                .build();
        kubernetes.namespaces().resource(target).create();

        ServiceAccount serviceAccount = new ServiceAccountBuilder()
                .withNewMetadata().withName(NAME).withNamespace(namespace).endMetadata()
                .build();
        kubernetes.serviceAccounts().inNamespace(namespace).resource(serviceAccount).create();

        Deployment deployment = deployment(namespace, environment, withProjectedToken);
        kubernetes.apps().deployments().inNamespace(namespace).resource(deployment).create();
        kubernetes.apps().deployments().inNamespace(namespace).withName(NAME)
                .waitUntilReady(5, TimeUnit.MINUTES);
    }

    private static Deployment deployment(String namespace, Map<String, String> environment,
                                         boolean withProjectedToken) {
        Map<String, String> labels = Map.of("app", NAME);
        DeploymentBuilder builder = new DeploymentBuilder()
                .withNewMetadata().withName(NAME).withNamespace(namespace).withLabels(labels).endMetadata()
                .withNewSpec()
                .withReplicas(1)
                .withNewSelector().withMatchLabels(labels).endSelector()
                .withNewTemplate()
                .withNewMetadata().withLabels(labels).endMetadata()
                .withNewSpec()
                .withServiceAccountName(NAME)
                .addNewContainer()
                .withName(NAME)
                .withImage(IMAGE)
                .addNewPort().withContainerPort(PORT).endPort()
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
                    .addToVolumeMounts(ProjectedToken.mount())
                    .endContainer()
                    .addToVolumes(ProjectedToken.volume())
                    .endSpec().endTemplate().endSpec();
        }
        return builder.build();
    }

    public static LocalPortForward forwardPort(KubernetesClient kubernetes, String namespace) {
        Pod pod = kubernetes.pods().inNamespace(namespace).withLabel("app", NAME).list().getItems().stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no pod of " + NAME + " in namespace " + namespace));
        return kubernetes.pods().inNamespace(namespace).withName(pod.getMetadata().getName()).portForward(PORT);
    }

    /**
     * The container is running long before the application is listening, and the boot itself includes the Consul
     * login, so the status is polled instead of being read once.
     */
    public static JsonNode awaitLoginStatus(LocalPortForward portForward) {
        return Awaitility.await("the service answers on /login-status")
                .atMost(Duration.ofMinutes(3))
                .pollInterval(Duration.ofSeconds(3))
                .ignoreExceptions()
                .until(() -> loginStatus(portForward), status -> status != null);
    }

    /** One read of the status, or null while the service is not answering yet. */
    public static JsonNode loginStatus(LocalPortForward portForward) {
        ConsulClient service = new ConsulClient("http://localhost:" + portForward.getLocalPort(), null);
        ConsulClient.Response response = service.get("/login-status");
        return response.isSuccessful() ? response.json() : null;
    }
}
