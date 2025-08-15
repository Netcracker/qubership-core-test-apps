package com.netcracker.it.meshtestservicespring;

import com.netcracker.cloud.junit.cloudcore.extension.annotations.Cloud;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.EnableExtension;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.PortForward;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.Value;
import com.netcracker.cloud.junit.cloudcore.extension.service.PortForwardService;
import com.netcracker.it.meshtestservicespring.model.BGContextResponse;
import com.netcracker.it.meshtestservicespring.model.Plugin;
import com.netcracker.it.meshtestservicespring.utils.ClosablePortForward;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.networking.v1beta1.Ingress;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Request;
import okhttp3.Response;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URL;
import java.util.List;
import java.util.Optional;

import static com.netcracker.it.meshtestservicespring.CommonOperations.*;
import static com.netcracker.it.meshtestservicespring.Const.*;
import static com.netcracker.it.meshtestservicespring.ResourceOperations.removeBluegreenPlugin;
import static com.netcracker.it.meshtestservicespring.model.Metrics.METRICS_AFTER_COMMIT;
import static com.netcracker.it.meshtestservicespring.model.Operation.WARMUP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Slf4j
@EnableExtension
public class CommitIT {

	//    @Named(INTERNAL_GW_SERVICE_NAME)
	//    @Scheme("http")
	//    @PortForward
	//    @Namespace(property = CONTROLLER_NAMESPACE_ENV_NAME)
	@PortForward(serviceName = @Value(INTERNAL_GW_SERVICE_NAME), cloud = @Cloud(namespace = @Value(prop = CONTROLLER_NAMESPACE_ENV_NAME)))
	private static URL internalGWServerUrl;

	//    @Named(PUBLIC_GW_SERVICE_NAME)
	//    @Scheme("http")
	//    @Namespace(property = CONTROLLER_NAMESPACE_ENV_NAME)
	//    @PortForward
	@PortForward(serviceName = @Value(PUBLIC_GW_SERVICE_NAME), cloud = @Cloud(namespace = @Value(prop = CONTROLLER_NAMESPACE_ENV_NAME)))
	private static URL publicGWServerUrlController;

	//    @Client
	//    @Namespace(property = ORIGIN_NAMESPACE_ENV_NAME)
	@Cloud(namespace = @Value(prop = ORIGIN_NAMESPACE_ENV_NAME))
	private static KubernetesClient platformClientOrigin;
	//    private static String token;
	//    private static ITHelper itHelper;

	@Cloud
	private static PortForwardService portForwardService;

	@BeforeAll
	public static void initParentClass() throws Exception {
		//        itHelper = new ITHelper(internalGWServerUrl, platformClientOrigin);
		//        token = itHelper.getTokenService().loginAsCloudAdmin();
	}

	@Test
	@Tag("bg-e2e-phase:after-commit-#1[baseline]")
	public void testNodePorts1() throws Exception {
		validateNodePorts(platformClientOrigin, PEER_NAMESPACE, internalGWServerUrl);
	}

	@Test
	@Tag("bg-e2e-phase:after-commit-#2[baseline]")
	public void testNodePorts2() throws Exception {
		validateNodePorts(platformClientOrigin, ORIGIN_NAMESPACE, internalGWServerUrl);
	}

	@Test
	@Tag("bg-e2e-phase:after-commit-#1[baseline]")
    @Disabled
	public void testCheckAllPodsScaledTo0AndPerformRequestCommit1() throws Exception {
		testCheckAllPodsScaledTo0AndPerformRequest(PEER_NAMESPACE, ORIGIN_NAMESPACE, "v2");
	}

	@Test
	@Tag("bg-e2e-phase:after-commit-#2[baseline]")
    @Disabled
	public void testCheckAllPodsScaledTo0AndPerformRequestCommit2() throws Exception {
		testCheckAllPodsScaledTo0AndPerformRequest(ORIGIN_NAMESPACE, PEER_NAMESPACE, "v3");
	}

//	@Test
//	@Tag("bg-e2e-phase:after-commit-#1[baseline]")
//	@Tag("bg-e2e-phase:after-commit-#2[baseline]")
//	public void testBGAvailableOperations1And2() throws IOException {
//		testBGAvailableOperations(platformClientOrigin, WARMUP);
//	}

	@Test
	@Tag("bg-e2e-phase:after-commit-#1[baseline]")
	@Tag("bg-e2e-phase:after-commit-#2[baseline]")
    @Disabled
	public void testBgMetrics1And2() throws IOException {
		testBgMetrics(platformClientOrigin, METRICS_AFTER_COMMIT);
	}

	public void testCheckAllPodsScaledTo0AndPerformRequest(String active, String idle, String activeXVersion) throws Exception {
		//Wait until commit is done
		waitUntilTasksAreCompleted(platformClientOrigin);

		// Check that all pods (if any) in ns-1 are scaled to 0
		PodList podList = platformClientOrigin.pods().inNamespace(idle).list();
		assertEquals(0,podList.getItems().size());

		List<Ingress> ingressList = platformClientOrigin.network().ingresses().inNamespace(CONTROLLER_NAMESPACE).list().getItems();
		Optional<Ingress> foundIngress = ingressList.stream().filter(ingress -> ingress.getMetadata().getName().equals(INGRESS_GW_INGRESS_NAME_IN_CONTROLLER)).findFirst();
		assertTrue(foundIngress.isPresent());
		String foundRouteHost = foundIngress.get().getSpec().getRules().get(0).getHost();

		//Perform all test without headers, check that response is from ns-2
		testIngressWithoutHeader(PROTOCOL_PREFIX + foundRouteHost, 200, active);

		//Check that routing is not possible with headers.
		testIngressWithHeader(PROTOCOL_PREFIX + foundRouteHost, 200, active, activeXVersion, X_VERSION_NAME_VALUE_CANDIDATE);
		testIngressWithHeader(PROTOCOL_PREFIX + foundRouteHost, 200, active, activeXVersion, X_VERSION_NAME_VALUE_LEGACY);
		testIngressWithHeader(PROTOCOL_PREFIX + foundRouteHost, 404, "", "", X_VERSION_NAME_VALUE_UNKNOWN);
	}

	@Test
	@Tag("bg-e2e-phase:after-commit-#1[baseline]")
    @Disabled
	public void testBgPlugin1() throws IOException {
		if (!SKIP_BG_PLUGIN_TESTS.equals("true")) {
            try (ClosablePortForward portForward = new ClosablePortForward(portForwardService,
                    BG_PLUGIN_NAMESPACE, BG_PLUGIN_SERVICE_NAME, 8080)) {
                CommonOperations.testBgPlugin(portForward.getUrl(), "commit");
            }
		}
	}

	@Test
	@Tag("bg-e2e-phase:after-commit-#1[baseline]")
    @Disabled
	public void testRemoveBgPlugin() throws Exception {
		try {
			removeBluegreenPlugin(platformClientOrigin);
		} catch (Exception e) {
			log.error(ExceptionUtils.getStackTrace(e));
			throw e;
		}
	}

	@Test
	@Tag("bg-e2e-phase:after-commit-#2[baseline]")
    @Disabled
	public void testBgPlugin2() throws IOException {
		BGContextResponse bgContextResponse = CommonOperations.getBGOperationStatus(platformClientOrigin);
		List<Plugin> plugins = bgContextResponse.getBGContext().getPlugins();
		assertTrue(plugins == null || plugins.isEmpty());
	}
}
