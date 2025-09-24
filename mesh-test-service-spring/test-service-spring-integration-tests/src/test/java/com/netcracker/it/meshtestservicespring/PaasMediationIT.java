package com.netcracker.it.meshtestservicespring;

import com.netcracker.cloud.junit.cloudcore.extension.annotations.Cloud;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.EnableExtension;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.PortForward;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.Value;
import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.extern.slf4j.Slf4j;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

@Slf4j
@EnableExtension
@Tag("bg-e2e-phase:after-deploy-composite[satellite]/[baseline]")
@Disabled
public class PaasMediationIT {

	@PortForward(serviceName = @Value(INTERNAL_GW_SERVICE_NAME), cloud = @Cloud(namespace = @Value(prop = ORIGIN_NAMESPACE_ENV_NAME)))
	private static URL internalGWServerUrl;

	@Cloud(namespace = @Value(prop = ORIGIN_NAMESPACE_ENV_NAME))
	private static KubernetesClient platformClient;
	private static String namespace;
	private static String ingressName;
	private static String ingressNameInController;

	@PortForward(serviceName = @Value(PUBLIC_GW_SERVICE_NAME), cloud = @Cloud(namespace = @Value(prop = PEER_NAMESPACE_ENV_NAME)))
	private static URL publicGWServerUrlPeer;

	@Cloud(namespace = @Value(prop = PEER_NAMESPACE_ENV_NAME))
	private static KubernetesClient platformClientPeer;


	@BeforeAll
	public static void initParentClass() throws Exception {
		namespace = platformClient.getNamespace();
		ingressName = INGRESS_GW_INGRESS_NAME + "-from-paas-mediation";
		ingressNameInController = ingressName + "-" + namespace;
	}


	@Test// Check that if ingress is created via paas-mediation it is replicated on Edge Router and communication is possible with and without header (duplicate ingress for test-ingress-gw).
	public void testCheckOnControllerIngressFromPaasMediation() throws IOException {
		//Check ingress resource (Created in BeforeWarmupIT.java)
		List<Ingress> ingressList = platformClient.network().v1().ingresses().inNamespace(CONTROLLER_NAMESPACE).list().getItems();
		Optional<Ingress> foundIngress = ingressList.stream().filter(ingress -> ingress.getMetadata().getName().equals(ingressNameInController)).findFirst();
		assertTrue(foundIngress.isPresent());

		String foundRouteHost = foundIngress.get().getSpec().getRules().get(0).getHost();

		//Check communication without header
		testIngressWithoutHeader(PROTOCOL_PREFIX + foundRouteHost, 200, ORIGIN_NAMESPACE);

		//Check communication with header
		testIngressWithHeader(PROTOCOL_PREFIX + foundRouteHost, 200, PEER_NAMESPACE, "v2", X_VERSION_NAME_VALUE_CANDIDATE);
	}
}
