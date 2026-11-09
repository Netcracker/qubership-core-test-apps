package com.netcracker.cloud.core.configservermigration.consulacl;

import com.netcracker.cloud.core.configservermigration.stand.Cluster;
import com.netcracker.cloud.core.configservermigration.stand.Http;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.Cloud;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.EnableExtension;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.IntValue;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.PortForward;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.Value;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * core-legacy-api in the Consul auth mode {@code k8s}, started while the Consul ACL operator is stopped: the service
 * cannot log in until the operator applies its {@code ConsulACL}, and becomes ready once it has. Restarts by the
 * startup probe in between are allowed.
 *
 * <p>The class stops the operator after the previous core-legacy-api is gone, because the operator has to release
 * the {@code ConsulACL} of that release first. It watches the service for a while once its container has started, and
 * then starts the operator again.
 */
@EnableExtension
@Tag("core-legacy-api-consul-acl")
class StartBeforeConsulAclIT {

    private static final String PROFILE = "start-before-consul-acl";
    /** How long the service is watched with the operator stopped; shorter than the startup probe allows. */
    private static final Duration WITHOUT_OPERATOR = Duration.ofSeconds(30);

    @Cloud(namespace = @Value(Cluster.CORE_NAMESPACE))
    private KubernetesClient kubernetes;

    @PortForward(serviceName = @Value(Cluster.CONSUL_SERVER), port = @IntValue(8500),
            cloud = @Cloud(namespace = @Value(Cluster.CONSUL_NAMESPACE)))
    private URL consulUrl;

    private ConsulAclStand stand;
    private ConsulApi admin;
    /** The most ready replicas seen while the operator was stopped. */
    private int mostReadyWithoutOperator;
    /** The exit code of the make that installed the service and waited for its rollout. */
    private int deployExitCode;

    @BeforeAll
    void deployWhileTheOperatorIsStopped() {
        stand = new ConsulAclStand(kubernetes, consulUrl, true);
        admin = stand.consulAsAdmin();
        stand.uninstall();
        Process deploy = null;
        try {
            stand.scaleAclOperator(0);
            deploy = stand.startDeploy("k8s");
            await("a container of " + ConsulAclStand.SERVICE + " starts")
                    .atMost(Duration.ofMinutes(5))
                    .pollInterval(Duration.ofSeconds(2))
                    .until(stand::containerStarted);
            await("the ready replicas of " + ConsulAclStand.SERVICE + " are sampled without the operator")
                    .during(WITHOUT_OPERATOR)
                    .atMost(WITHOUT_OPERATOR.plusSeconds(10))
                    .pollInterval(Duration.ofSeconds(2))
                    .until(() -> {
                        mostReadyWithoutOperator = Math.max(mostReadyWithoutOperator,
                                readyReplicas(stand.deployment()));
                        return true;
                    });
        } catch (RuntimeException e) {
            if (deploy != null) {
                deploy.destroyForcibly();
            }
            throw e;
        } finally {
            stand.scaleAclOperator(1);
        }
        deployExitCode = LocalDev.awaitExit(deploy);
    }

    @AfterAll
    void close() {
        if (stand != null) {
            stand.close();
        }
    }

    @Test
    void theServiceIsNotReadyWhileTheOperatorIsStopped() {
        assertEquals(0, mostReadyWithoutOperator, "most ready replicas of " + ConsulAclStand.SERVICE + " over "
                + WITHOUT_OPERATOR.toSeconds() + " s with the operator stopped");
    }

    /** make deploy-core-legacy-api waits for the rollout, up to 5 minutes, and fails when it does not complete. */
    @Test
    void theServiceBecomesReadyAfterTheOperatorAppliesItsConsulAcl() {
        assertEquals(0, deployExitCode, "exit code of make deploy-core-legacy-api, see its output in the log");
    }

    @Test
    void aPropertyWrittenThroughTheServiceIsStoredInConsul() {
        String path = "/" + ConsulAclStand.APPLICATION + "/" + PROFILE;
        Http.Response response = stand.coreLegacyApi().post(path, Map.of("acl.probe", "written after the operator"));

        assertEquals(201, response.status(), "POST " + path + " answered " + response.body());
        assertEquals("written after the operator", ConsulAclStand.storedValue(admin, PROFILE, "acl.probe"),
                "acl.probe in Consul");
    }

    private static int readyReplicas(Deployment deployment) {
        return deployment.getStatus() == null
                ? 0
                : Objects.requireNonNullElse(deployment.getStatus().getReadyReplicas(), 0);
    }
}
