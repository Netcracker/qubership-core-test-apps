package com.netcracker.it.meshtestservicespring;

import com.netcracker.cloud.junit.cloudcore.extension.annotations.Cloud;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.EnableExtension;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.PortForward;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.Value;
import io.fabric8.kubernetes.api.model.networking.v1beta1.Ingress;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URL;
import java.util.List;
import java.util.Optional;

import static com.netcracker.it.meshtestservicespring.CommonOperations.*;
import static com.netcracker.it.meshtestservicespring.Const.*;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnableExtension
@Tag("bg-e2e-phase:after-deploy-composite")
public class BGCompositeIT {
	//    @Named(PRIVATE_GW_SERVICE_NAME)
	//    @Namespace(property = SATELLITE_ENV_NAME)
	//    @Scheme("http")
	//    @PortForward
	@PortForward(serviceName = @Value(PRIVATE_GW_SERVICE_NAME), cloud = @Cloud(namespace = @Value(prop = SATELLITE_ENV_NAME)))
	private static URL privateGWServerUrlSatellite;

	//    @Named(INTERNAL_GW_SERVICE_NAME)
	//    @Namespace(property = PEER_NAMESPACE_ENV_NAME)
	//    @Scheme("http")
	//    @PortForward
	@PortForward(serviceName = @Value(INTERNAL_GW_SERVICE_NAME), cloud = @Cloud(namespace = @Value(prop = PEER_NAMESPACE_ENV_NAME)))
	private static URL internalGWServerUrlPeer;

	//    @Client
    @Cloud
	private static KubernetesClient platformClientSatellite;

	//    @Client
    @Cloud
	private static KubernetesClient platformClientPeer;
	//    private static String cloudAdminTokenSatellite;
	//    private static String cloudAdminTokenPeer;
	//    private static ITHelper itHelperSatellite;
	//    private static ITHelper itHelperPeer;

	@BeforeAll
	public static void initParentClass() throws Exception {
		//        itHelperSatellite = new ITHelper(privateGWServerUrlSatellite, platformClientSatellite);
		//        cloudAdminTokenSatellite = itHelperSatellite.getTokenService().loginAsCloudAdmin();
		//        itHelperPeer = new ITHelper(internalGWServerUrlPeer, platformClientPeer);
		//        cloudAdminTokenPeer = itHelperPeer.getTokenService().getTokenBuilder().asUser().asCloudAdmin().reLogin().login();
	}

	@Test
	public void testPerformRequest() throws IOException {
		List<Ingress> ingressList = platformClientPeer.network().ingresses().inNamespace(CONTROLLER_NAMESPACE).list().getItems();
		Optional<Ingress> foundIngress = ingressList.stream().filter(ingress -> ingress.getMetadata().getName().equals(INGRESS_GW_INGRESS_NAME_IN_CONTROLLER)).findFirst();
		assertTrue(foundIngress.isPresent());
		String foundRouteHost = foundIngress.get().getSpec().getRules().get(0).getHost();

		List<Ingress> ingressListSatellite = platformClientSatellite.network().ingresses().inNamespace(SATELLITE).list().getItems();
		Optional<Ingress> foundIngressSatellite = ingressListSatellite.stream().filter(ingress -> ingress.getMetadata().getName().equals(INGRESS_GW_INGRESS_NAME)).findFirst();
		assertTrue(foundIngressSatellite.isPresent());
		String foundRouteHostSatellite = foundIngressSatellite.get().getSpec().getRules().get(0).getHost();

		//Check communication from satellite to Base via service by header (to Candidate) and without header (to Active).
		testIngressWithHeader(PROTOCOL_PREFIX + foundRouteHostSatellite, 200, PEER_NAMESPACE, "v2", QUARKUS_SERVICE_NAME, X_VERSION_NAME_VALUE_CANDIDATE, "/quarkus?namespace=" + CONTROLLER_NAMESPACE);
		testIngressWithoutHeader(PROTOCOL_PREFIX + foundRouteHostSatellite, 200, ORIGIN_NAMESPACE, QUARKUS_SERVICE_NAME,  "/quarkus?namespace=" + CONTROLLER_NAMESPACE);

		//Check communication from Candidate to Satellite by Service.
		testIngressWithHeader(PROTOCOL_PREFIX + foundRouteHost, 200, SATELLITE, "v2", QUARKUS_SERVICE_NAME, X_VERSION_NAME_VALUE_CANDIDATE,  "/quarkus?namespace=" + SATELLITE);

		//Check that token got on base with header (on base Candidate) can be used on Satellite
		testRequestWithoutHeader(PROTOCOL_PREFIX + foundRouteHostSatellite + "/api/v1/" + SPRING_SERVICE_NAME + "/hello", 200, SATELLITE, SPRING_SERVICE_NAME, false);
	}
}
