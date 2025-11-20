package com.netcracker.it.spring;

import com.netcracker.cloud.junit.cloudcore.extension.annotations.Cloud;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.EnableExtension;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.PortForward;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.Value;
import com.netcracker.cloud.junit.cloudcore.extension.service.PortForwardService;
import com.netcracker.it.spring.model.BGContext;
import com.netcracker.it.spring.model.BGContextResponse;
import com.netcracker.it.spring.model.Plugin;
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
import static com.netcracker.it.spring.ResourceOperations.removeHorizontalPodAutoscaler;
import static com.netcracker.it.spring.model.Metrics.METRICS_AFTER_PROMOTE;
import static com.netcracker.it.spring.model.Operation.COMMIT;
import static com.netcracker.it.spring.model.Operation.ROLLBACK;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Slf4j
@EnableExtension
@Disabled
public class PromoteIT {

    @Cloud
    private static PortForwardService portForwardService;

	@PortForward(serviceName = @Value(INTERNAL_GW_SERVICE_NAME), cloud = @Cloud(namespace = @Value(prop = ORIGIN_NAMESPACE_ENV_NAME)))
	private static URL internalGWServerUrlOrigin;

	@Cloud(namespace = @Value(prop = ORIGIN_NAMESPACE_ENV_NAME))
	private static KubernetesClient platformClientOrigin;

	@PortForward(serviceName = @Value(PUBLIC_GW_SERVICE_NAME), cloud = @Cloud(namespace = @Value(prop = CONTROLLER_NAMESPACE_ENV_NAME)))
	private static URL publicGWServerUrlController;

	@PortForward(serviceName = @Value(PRIVATE_GW_SERVICE_NAME), cloud = @Cloud(namespace = @Value(prop = PEER_NAMESPACE_ENV_NAME)))
	private static URL privateGWServerUrlPeer;

	@Cloud(namespace = @Value(prop = PEER_NAMESPACE_ENV_NAME))
	private static KubernetesClient platformClientPeer;

	private static BGContext bgContext;

	@BeforeAll
	public static void initParentClass() throws Exception {
		bgContext = getBGContext(platformClientOrigin);
	}

	@Test
	@Tag("bg-e2e-phase:after-promote-#1[baseline]")
	@Tag("bg-e2e-phase:after-promote-#2[baseline]")
	public void testNodePorts1() throws Exception {
		validateNodePorts(platformClientPeer, PEER_NAMESPACE, privateGWServerUrlPeer);
	}

	@Test
	@Tag("bg-e2e-phase:after-promote-#3[baseline]")
	public void testNodePorts2() throws Exception {
		validateNodePorts(platformClientOrigin, ORIGIN_NAMESPACE, internalGWServerUrlOrigin);
	}

	@Test
	@Tag("bg-e2e-phase:after-promote-#1[baseline]")
	@Tag("bg-e2e-phase:after-promote-#2[baseline]")
    	public void testPerformRequestsPromote1And2() throws Exception {
		testPerformRequests(PEER_NAMESPACE, ORIGIN_NAMESPACE, "v2", "v1");
	}

	@Test
	@Tag("bg-e2e-phase:after-promote-#3[baseline]")
    	public void testPerformRequestsPromote3() throws Exception {
		testPerformRequests(ORIGIN_NAMESPACE, PEER_NAMESPACE, "v3", "v2");
	}

	@Test
	@Tag("bg-e2e-phase:after-promote-#1[baseline]")
	@Tag("bg-e2e-phase:after-promote-#2[baseline]")
	@Tag("bg-e2e-phase:after-promote-#3[baseline]")
	public void testAvailableOperations() throws IOException {
		testBGAvailableOperations(platformClientOrigin, ROLLBACK, COMMIT);
	}

	@Test
	@Tag("bg-e2e-phase:after-promote-#1[baseline]")
	@Tag("bg-e2e-phase:after-promote-#2[baseline]")
	@Tag("bg-e2e-phase:after-promote-#3[baseline]")
    	public void testMetrics() throws IOException {
		testBgMetrics(platformClientOrigin, METRICS_AFTER_PROMOTE);
	}

	@Test
	@Tag("bg-e2e-phase:after-promote-#1[baseline]")
    	public void testScaling1() throws Exception {
		try {
			String activeNs = getActiveNamespace(platformClientOrigin);
			int replicas = getCountOfReplicas(platformClientOrigin, activeNs, MESH_TEST_SERVICE_GO_V1);
			log.info("Count of replicas of {} on {} is {}", MESH_TEST_SERVICE_GO_V1, activeNs, replicas);
			assertTrue(replicas >= HPA_TEST_COUNT_OF_REPLICAS-2);

			scaleDeployment(platformClientOrigin, activeNs, MESH_TEST_SERVICE_GO_V1, HPA_TEST_COUNT_OF_REPLICAS_FOR_ROLLBACK);
		} catch (Exception e) {
			log.error(ExceptionUtils.getStackTrace(e));
			throw e;
		}
	}

	@Test
	@Tag("bg-e2e-phase:after-promote-#2[baseline]")
    	public void testScaling2() throws Exception {
		try {
			String activeNs = getActiveNamespace(platformClientOrigin);
			int replicas = getCountOfReplicas(platformClientOrigin, activeNs, MESH_TEST_SERVICE_GO_V1);
			log.info("Count of replicas of {} on {} is {}", MESH_TEST_SERVICE_GO_V1, activeNs, replicas);
			assertTrue(replicas >= HPA_TEST_COUNT_OF_REPLICAS-2);
		} catch (Exception e) {
			log.error(ExceptionUtils.getStackTrace(e));
			throw e;
		}
	}

	@Test
	@Tag("bg-e2e-phase:after-promote-#3[baseline]")
    	public void testScaling3() throws Exception {
		try {
			String activeNs = getActiveNamespace(platformClientOrigin);
			int replicas = getCountOfReplicas(platformClientOrigin, activeNs, MESH_TEST_SERVICE_GO_V1);
			log.info("Count of replicas of {} on {} is {}", MESH_TEST_SERVICE_GO_V1, activeNs, replicas);
			removeHorizontalPodAutoscaler(platformClientOrigin, ORIGIN_NAMESPACE, MESH_TEST_SERVICE_GO_HPA);
			removeHorizontalPodAutoscaler(platformClientOrigin, PEER_NAMESPACE, MESH_TEST_SERVICE_GO_HPA);
			scaleDeployment(platformClientOrigin, ORIGIN_NAMESPACE, MESH_TEST_SERVICE_GO_V1, 1);
			scaleDeployment(platformClientOrigin, PEER_NAMESPACE, MESH_TEST_SERVICE_GO_V1, 1);
			assertTrue(replicas >= HPA_TEST_COUNT_OF_REPLICAS-2);
		} catch (Exception e) {
			log.error(ExceptionUtils.getStackTrace(e));
			throw e;
		}
	}

	public void testPerformRequests(String active, String legacy, String activeXVersion, String legacyXVersion) throws Exception {
		//Wait until promote is done
		waitUntilTasksAreCompleted(platformClientOrigin);

		List<Ingress> ingressList = platformClientOrigin.network().ingresses().inNamespace(CONTROLLER_NAMESPACE).list().getItems();
		Optional<Ingress> foundIngress = ingressList.stream().filter(ingress -> ingress.getMetadata().getName().equals(INGRESS_GW_INGRESS_NAME_IN_CONTROLLER)).findFirst();
		assertTrue(foundIngress.isPresent());
		String foundRouteHost = foundIngress.get().getSpec().getRules().get(0).getHost();

		//Perform all tests without header, but checking that Active is now in ns-2.
		testIngressWithoutHeader(PROTOCOL_PREFIX + foundRouteHost, 200, active);

		//Perform tests with x-version-name=Legacy header
		testIngressWithHeader(PROTOCOL_PREFIX + foundRouteHost, 200, legacy, legacyXVersion, X_VERSION_NAME_VALUE_LEGACY);

		//Perform tests with x-version-name=Candidate header
		testIngressWithHeader(PROTOCOL_PREFIX + foundRouteHost, 200, active, activeXVersion, X_VERSION_NAME_VALUE_CANDIDATE);

		testIngressWithHeader(PROTOCOL_PREFIX + foundRouteHost, 404, "", "", X_VERSION_NAME_VALUE_UNKNOWN);

		//Check that attempt to execute promote twice returns an error
		String bgCreds = getBGOperatorCredentials(platformClientOrigin);
		failOperation(platformClientOrigin, "promote", bgCreds);
	}

	@Test
	@Tag("bg-e2e-phase:after-promote-#1[baseline]")
	@Tag("bg-e2e-phase:after-promote-#2[baseline]")
    	public void testBgPlugin1() throws Exception {
        try (ClosablePortForward portForward =
                     new ClosablePortForward(portForwardService, BG_PLUGIN_NAMESPACE,BG_PLUGIN_SERVICE_NAME, 8080 )) {
            CommonOperations.testBgPlugin(portForward.getUrl(), "promote");
        }
	}

	@Test
	@Tag("bg-e2e-phase:after-promote-#3[baseline]")
    	public void testBgPlugin2() throws IOException {
		BGContextResponse bgContextResponse = CommonOperations.getBGOperationStatus(platformClientOrigin);
		List<Plugin> plugins = bgContextResponse.getBGContext().getPlugins();
		assertTrue(plugins == null || plugins.isEmpty());
	}
}
