package com.netcracker.it.spring;

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

import static com.netcracker.it.spring.Const.*;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Slf4j
@EnableExtension
@Tag("bg-e2e-phase:after-deploy-composite[satellite]/[baseline]")
@Disabled
public class NegativeScenariosIT {

	@PortForward(serviceName = @Value(INTERNAL_GW_SERVICE_NAME), cloud = @Cloud(namespace = @Value(prop = ORIGIN_NAMESPACE_ENV_NAME)))
	private static URL internalGWServerUrl;

	@Cloud(namespace = @Value(prop = ORIGIN_NAMESPACE_ENV_NAME))
	private static KubernetesClient platformClient;

	private static String ingressUrl;

	@BeforeAll
	public void getHost() throws IOException {
		List<Ingress> ingressListController = platformClient.network().v1().ingresses().inNamespace(CONTROLLER_NAMESPACE).list().getItems();
        Optional<Ingress> testGatewayIngress = ingressListController.stream().filter(ingress -> ingress.getMetadata().getName().equals(INGRESS_GW_INGRESS_NAME_IN_CONTROLLER)).findFirst();
		assertTrue(testGatewayIngress.isPresent());
		String testGatewayHost = testGatewayIngress.get().getSpec().getRules().get(0).getHost();
		ingressUrl = PROTOCOL_PREFIX + testGatewayHost;
	}

	@Test
	public void testResponseErrorOnLegacy() throws IOException {
		CommonOperations.testIngressWithHeader(ingressUrl, 404, "", "", X_VERSION_NAME_VALUE_UNKNOWN);
	}
}
