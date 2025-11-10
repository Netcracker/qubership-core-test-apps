package com.netcracker.it.spring;

import com.netcracker.cloud.junit.cloudcore.extension.annotations.Cloud;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.EnableExtension;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.PortForward;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.Value;
import com.netcracker.cloud.junit.cloudcore.extension.service.PortForwardService;
import com.netcracker.it.spring.model.Operation;
import com.netcracker.it.spring.utils.ClosablePortForward;
import io.fabric8.kubernetes.api.model.networking.v1beta1.Ingress;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URL;
import java.util.List;
import java.util.Optional;

import static com.netcracker.it.spring.CommonOperations.*;
import static com.netcracker.it.spring.Const.*;
import static com.netcracker.it.spring.model.Metrics.METRICS_AFTER_ROLLBACK;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Slf4j
@EnableExtension
@Tag("bg-e2e-phase:after-rollback-#1[baseline]")
@Disabled
public class RollbackIT {
	@Cloud
	static PortForwardService portForwardService;

	@PortForward(serviceName = @Value(INTERNAL_GW_SERVICE_NAME), cloud = @Cloud(namespace = @Value(prop = ORIGIN_NAMESPACE_ENV_NAME)))
	private static URL internalGWServerUrlOrigin;

	@Cloud(namespace = @Value(prop = ORIGIN_NAMESPACE_ENV_NAME))
	private static KubernetesClient platformClient;

	@PortForward(serviceName = @Value(PUBLIC_GW_SERVICE_NAME), cloud = @Cloud(namespace = @Value(prop = PEER_NAMESPACE_ENV_NAME)))
	private static URL publicGWServerUrlPeer;

	@Cloud(namespace = @Value(prop = PEER_NAMESPACE_ENV_NAME))
	private static KubernetesClient platformClientPeer;

	@BeforeAll
	public static void initParentClass() throws Exception {
	}

	@Test
	public void testNodePorts() throws Exception {
		if (!SKIP_NODE_PORT_TESTS.equals("true")) {
			validateNodePorts(platformClient, ORIGIN_NAMESPACE, internalGWServerUrlOrigin);
		}
	}

	@Test
    	public void testCheckPerformRequest() throws IOException {
		//Wait until promote is done
		waitUntilTasksAreCompleted(platformClient);

		List<Ingress> ingressList = platformClient.network().ingresses().inNamespace(CONTROLLER_NAMESPACE).list().getItems();
		Optional<Ingress> foundIngress = ingressList.stream().filter(ingress -> ingress.getMetadata().getName().equals(INGRESS_GW_INGRESS_NAME_IN_CONTROLLER)).findFirst();
		assertTrue(foundIngress.isPresent());
		String foundRouteHost = foundIngress.get().getSpec().getRules().get(0).getHost();

		//Check that all ingresses and services forwards requests to proper namespace
		testIngressWithoutHeader(PROTOCOL_PREFIX + foundRouteHost, 200, ORIGIN_NAMESPACE);
		testIngressWithHeader(PROTOCOL_PREFIX + foundRouteHost, 200, PEER_NAMESPACE, "v2", X_VERSION_NAME_VALUE_CANDIDATE);
		testIngressWithHeader(PROTOCOL_PREFIX + foundRouteHost, 200, ORIGIN_NAMESPACE, "v1", X_VERSION_NAME_VALUE_LEGACY);
		testIngressWithHeader(PROTOCOL_PREFIX + foundRouteHost, 404, "", "", X_VERSION_NAME_VALUE_UNKNOWN);

		//Check that attempt to execute rollback twice returns an error
		String bgCreds = getBGOperatorCredentials(platformClient);
		failOperation(platformClient, "rollback", bgCreds);
	}

	@Test
    	public void testCheckBGAvailableOperations() throws IOException {
		testBGAvailableOperations(platformClient, Operation.PROMOTE, Operation.COMMIT);
	}

	@Test
    	public void testMetrics() throws IOException {
		testBgMetrics(platformClient, METRICS_AFTER_ROLLBACK);
	}

	@Test
    	public void testScaling() throws Exception {
		try {
			String activeNs = getActiveNamespace(platformClient);
			int replicas = getCountOfReplicas(platformClient, activeNs, MESH_TEST_SERVICE_GO_V1);
			log.info("Count of replicas of {} on {} is {}", MESH_TEST_SERVICE_GO_V1, activeNs, replicas);
			assertTrue(replicas >= HPA_TEST_COUNT_OF_REPLICAS_FOR_ROLLBACK-2);

			scaleDeployment(platformClient, activeNs, MESH_TEST_SERVICE_GO_V1, HPA_TEST_COUNT_OF_REPLICAS);
		} catch (Exception e) {
			log.error(ExceptionUtils.getStackTrace(e));
			throw e;
		}
	}

	@Test
    	public void testBgPlugin() throws Exception {
        try (ClosablePortForward portForward =
                new ClosablePortForward(portForwardService, BG_PLUGIN_NAMESPACE,BG_PLUGIN_SERVICE_NAME, 8080 )) {
            CommonOperations.testBgPlugin(portForward.getUrl(), "rollback");
        }
	}
}
