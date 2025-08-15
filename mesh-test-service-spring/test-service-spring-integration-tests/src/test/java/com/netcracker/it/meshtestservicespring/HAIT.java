package com.netcracker.it.meshtestservicespring;

import com.netcracker.cloud.junit.cloudcore.extension.annotations.Cloud;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.EnableExtension;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.PortForward;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.Value;
import io.fabric8.kubernetes.api.model.ListOptions;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.networking.v1beta1.Ingress;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URL;
import java.util.List;
import java.util.Optional;

import static com.netcracker.it.meshtestservicespring.CommonOperations.*;
import static com.netcracker.it.meshtestservicespring.Const.*;
import static org.junit.jupiter.api.Assertions.*;

@Slf4j
@EnableExtension
@Tag("bg-e2e-phase:after-deploy-composite[satellite]/[baseline]")
public class HAIT {

	//    @Named(INTERNAL_GW_SERVICE_NAME)
	//    @Scheme("http")
	//    @PortForward
	//    @Namespace(property = ORIGIN_NAMESPACE_ENV_NAME)
	@PortForward(serviceName = @Value(INTERNAL_GW_SERVICE_NAME), cloud = @Cloud(namespace = @Value(prop = ORIGIN_NAMESPACE_ENV_NAME)))
	private static URL internalGWServerUrl;

	//    @Client
	//    @Namespace(property = ORIGIN_NAMESPACE_ENV_NAME)
	@Cloud(namespace = @Value(prop = ORIGIN_NAMESPACE_ENV_NAME))
	private static KubernetesClient platformClient;
	//    private static String tokenOrigin;
	//    private static ITHelper itHelper;

	//    @Named(PUBLIC_GW_SERVICE_NAME)
	//    @Scheme("http")
	//    @PortForward
	//    @Namespace(property = PEER_NAMESPACE_ENV_NAME)
	@PortForward(serviceName = @Value(PUBLIC_GW_SERVICE_NAME), cloud = @Cloud(namespace = @Value(prop = PEER_NAMESPACE_ENV_NAME)))
	private static URL publicGWServerUrlPeer;
	//    @Client
	//    @Namespace(property = PEER_NAMESPACE_ENV_NAME)
	@Cloud(namespace = @Value(prop = PEER_NAMESPACE_ENV_NAME))
	private static KubernetesClient platformClientPeer;
	//    private static ITHelper itHelperPeer;
	//    private static String tokenPeer;


	@BeforeAll
	public static void initParentClass() {
		//        itHelper = new ITHelper(internalGWServerUrl, platformClient);
		//        tokenOrigin = itHelper.getTokenService().loginAsCloudAdmin();
		//        itHelperPeer = new ITHelper(publicGWServerUrlPeer, platformClientPeer);
		//        tokenPeer = itHelperPeer.getTokenService().getTokenBuilder().asUser().asCloudAdmin().reLogin().login();
	}


	@Test
	public void testCheckPerformRequestsAfterReboot() throws IOException {
		ListOptions listOptions = new ListOptions();
		listOptions.setLabelSelector("name=" + EDGE_ROUTER_SERVICE_NAME);
		PodList podListBeforeReboot = platformClient.pods().inNamespace(CONTROLLER_NAMESPACE).list(listOptions);
		assertEquals(1, podListBeforeReboot.getItems().size());
		Pod edgeRouterPodBeforeReboot = podListBeforeReboot.getItems().get(0);

		//reboot
		platformClient.pods().inNamespace(CONTROLLER_NAMESPACE).withName(edgeRouterPodBeforeReboot.getMetadata().getName()).delete();

		//Wait until pod is new
		assertWithTimeout(() -> {
			PodList podListAfterReboot = platformClient.pods().inNamespace(CONTROLLER_NAMESPACE).list(listOptions);
			assertEquals(1, podListAfterReboot.getItems().size());
			Pod edgeRouterPodAfterReboot = podListAfterReboot.getItems().get(0);
			assertNotEquals(edgeRouterPodBeforeReboot.getMetadata().getName(), edgeRouterPodAfterReboot.getMetadata().getName());
		});

		List<Ingress> ingressList = platformClient.network().ingresses().inNamespace(CONTROLLER_NAMESPACE).list().getItems();
		Optional<Ingress> foundIngress = ingressList.stream().filter(ingress -> ingress.getMetadata().getName().equals(INGRESS_GW_INGRESS_NAME_IN_CONTROLLER)).findFirst();
		assertTrue(foundIngress.isPresent());
		String foundRouteHost = foundIngress.get().getSpec().getRules().get(0).getHost();

		//Wait until new pod is up
		assertWithTimeout(() -> {
			//Check communication without header
			testIngressWithoutHeader(PROTOCOL_PREFIX + foundRouteHost, 200, ORIGIN_NAMESPACE);
		});

		//Check communication with header
		testIngressWithHeader(PROTOCOL_PREFIX + foundRouteHost, 200, PEER_NAMESPACE, "v2", X_VERSION_NAME_VALUE_CANDIDATE);
	}

	@Test
	public void testCheckPerformRequestsScale3() throws IOException {
		List<Ingress> ingressList = platformClient.network().ingresses().inNamespace(CONTROLLER_NAMESPACE).list().getItems();
		Optional<Ingress> foundIngress = ingressList.stream().filter(ingress -> ingress.getMetadata().getName().equals(INGRESS_GW_INGRESS_NAME_IN_CONTROLLER)).findFirst();
		assertTrue(foundIngress.isPresent());
		String foundRouteHost = foundIngress.get().getSpec().getRules().get(0).getHost();

		//Check communication without header before scale 3
		testIngressWithoutHeader(PROTOCOL_PREFIX + foundRouteHost, 200, ORIGIN_NAMESPACE);
		try {
			platformClient.apps().deployments().inNamespace(CONTROLLER_NAMESPACE).withName(EDGE_ROUTER_SERVICE_NAME).scale(3);

			//Wait until all pods have phase running
			ListOptions listOptions = new ListOptions();
			listOptions.setLabelSelector("name=" + EDGE_ROUTER_SERVICE_NAME);
			assertWithTimeout(() -> {
				PodList podList = platformClient.pods().inNamespace(CONTROLLER_NAMESPACE).list(listOptions);
				podList.getItems().stream().forEach(pod -> assertEquals("Running", pod.getStatus().getPhase()));
			});

			//Check communication without header
			testIngressWithoutHeader(PROTOCOL_PREFIX + foundRouteHost, 200, ORIGIN_NAMESPACE);

			//Check communication with header
			testIngressWithHeader(PROTOCOL_PREFIX + foundRouteHost, 200, PEER_NAMESPACE, "v2", X_VERSION_NAME_VALUE_CANDIDATE);
		} finally {
			platformClient.apps().deployments().inNamespace(CONTROLLER_NAMESPACE).withName(EDGE_ROUTER_SERVICE_NAME).scale(1);
		}
	}

	@AfterAll
	public void scale1() {
		platformClient.apps().deployments().inNamespace(CONTROLLER_NAMESPACE).withName(EDGE_ROUTER_SERVICE_NAME).scale(1);
	}
}
