package com.netcracker.it.meshtestservicespring;

import com.google.gson.Gson;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.Cloud;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.EnableExtension;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.PortForward;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.Value;
import io.fabric8.kubernetes.client.KubernetesClient;
import com.netcracker.it.meshtestservicespring.model.CompositeStructure;
import com.netcracker.it.meshtestservicespring.model.TraceResponse;
import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URL;
import java.util.Arrays;
import java.util.Optional;

import static com.netcracker.it.meshtestservicespring.CommonOperations.*;
import static com.netcracker.it.meshtestservicespring.Const.*;
import static org.junit.jupiter.api.Assertions.*;

@EnableExtension
@Tag("Composite")
public class CompositeIT {
	@PortForward(serviceName = @Value(PUBLIC_GW_SERVICE_NAME))
	private static URL publicGWServerUrl;

	@PortForward(serviceName = @Value(INTERNAL_GW_SERVICE_NAME))
	private static URL internalGWServerUrl;

	@PortForward(serviceName = @Value(SERVICE_NAME))
	private static URL compositeGWServerUrl;

	@Cloud
	private static KubernetesClient platformClient;

	private static String namespace;

	@BeforeAll
	public static void init() throws Exception {
		assertNotNull(publicGWServerUrl);
		assertNotNull(internalGWServerUrl);
		assertNotNull(platformClient);
	}

	@Test
	public void testRouteToQuarkusServiceInAnotherNamespace() throws IOException {
		//Get another satellite namespace
		Request prepRequest = new Request.Builder()
				.url(internalGWServerUrl+ "/api/v3/control-plane/composite-platform/namespaces")
				.get()
				.build();
		try (Response response = okHttpClient.newCall(prepRequest).execute()) {
			assertEquals(200, response.code());
			String compositeStructureJson = response.body().string();
			CompositeStructure compositeStructure = new Gson().fromJson(compositeStructureJson, CompositeStructure.class);
			String currentNamespace = platformClient.getNamespace();
			String[] satellites = compositeStructure.getSatellites();
			Optional<String> anotherSatellite = Arrays.stream(satellites).filter(satellite -> !satellite.equals(currentNamespace)).findFirst();
			assertTrue(anotherSatellite.isPresent());
			namespace = anotherSatellite.get();
		}

		//Send request to another satellite and check namespace in response
		HttpUrl url = new HttpUrl.Builder()
				.scheme("http")
				.host(SERVICE_NAME)
				.port(8080)
				.addPathSegment("api")
				.addPathSegment("v1")
				.addPathSegment("mesh-test-service-spring")
				.addPathSegment("hello")
				.addPathSegment("quarkus")
				.addQueryParameter("namespace", namespace)
				.build();
		Request request = new Request.Builder()
				.url(url)
				.get()
				.build();
		try (Response response = okHttpClient.newCall(request).execute()) {
			assertEquals(200, response.code());
			String jsonResponse = response.body().string();
			TraceResponse traceResponse = new Gson().fromJson(jsonResponse, TraceResponse.class);
			assertEquals("mesh-test-service-quarkus", traceResponse.getFamilyName());
			assertEquals(namespace, traceResponse.getNamespace());
		}
	}
}
