package com.netcracker.it.meshtestservicespring;

import com.google.gson.Gson;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.*;
import com.netcracker.it.meshtestservicespring.model.TraceResponse;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URL;

import static com.netcracker.it.meshtestservicespring.CommonOperations.*;
import static com.netcracker.it.meshtestservicespring.Const.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.*;

@EnableExtension
@Slf4j
@Tag("Mesh")
public class HttpIT {

	static String NAMESPACE = System.getenv("ENV_NAMESPACE");
//    static String CLOUD_NAME = System.getenv("CLOUD_NAME");

	@PortForward(serviceName = @Value(PUBLIC_GW_SERVICE_NAME))
	private static URL publicGWServerUrl;

	@PortForward(serviceName = @Value(PRIVATE_GW_SERVICE_NAME))
	private static URL privateGWServerUrl;

	@PortForward(serviceName = @Value(INTERNAL_GW_SERVICE_NAME))
	private static URL internalGWServerUrl;

	@PortForward(serviceName = @Value(SERVICE_NAME))
	private static URL compositeGWServerUrl;

	@PortForward(serviceName = @Value(EGRESS_GW_SERVICE_NAME))
	private static URL egressGWServerUrl;

	@PortForward(serviceName = @Value(EGRESS_GW_SERVICE_NAME), port = @IntValue(value = ENVOY_ADMIN_PORT))
	private static URL egressGWAdminServerUrl;

	@Cloud
	private static KubernetesClient platformClient;

	@BeforeAll
	public static void init() throws Exception {
		assertNotNull(publicGWServerUrl);
		assertNotNull(internalGWServerUrl);
		assertNotNull(egressGWServerUrl);
		assertNotNull(platformClient);
	}

	@Test
	public void testRouteRegisteredByLib() throws IOException {
		Request request = new Request.Builder()
				.url(publicGWServerUrl + "/api/v1/mesh-test-service-spring/hello")
				.get()
				.build();
		try (Response response = okHttpClient.newCall(request).execute()) {
			assertEquals(200, response.code());
			String jsonResponse = response.body().string();
			TraceResponse traceResponse = new Gson().fromJson(jsonResponse, TraceResponse.class);
			assertEquals(SERVICE_NAME, traceResponse.getFamilyName());
		}
	}

	@Test
	public void testRouteRegisteredDeclarative() throws IOException {
		Request request = new Request.Builder()
				.url(publicGWServerUrl + "/api/v1/mesh-test-service-spring/declarative_hello")
				.get()
				.build();
		try (Response response = okHttpClient.newCall(request).execute()) {
			assertEquals(200, response.code());
			String stringResponse = response.body().string();
			assertEquals("Declarative hello from spring mesh test service", stringResponse);
		}
	}

	@Test
	public void testRouteRegisteredInCompositeGWWIthRoutingByHost() throws IOException {
		Request request = new Request.Builder()
				.url(compositeGWServerUrl+ "/api/v1/mesh-test-service-spring/declarative_hello")
				.get()
				.build();
		try (Response response = okHttpClient.newCall(request).execute()) {
			assertEquals(200, response.code());
			String stringResponse = response.body().string();
			assertEquals("Declarative hello from spring mesh test service", stringResponse);
		}
	}

	@Test
	public void testRouteRegisteredInEgressGWtoCP() throws IOException {
		Request request = new Request.Builder()
				.url(publicGWServerUrl + "/api/v1/mesh-test-service-spring/egress")
				.get()
				.build();
		try (Response response = okHttpClient.newCall(request).execute()) {
			assertEquals(200, response.code());
			String stringResponse = response.body().string();
			assertTrue(stringResponse.startsWith("Egress answered:"));
		}
	}

	@Test//Send not existed deployment version
	public void testContextPropagation() throws IOException {
		Request request = new Request.Builder()
				.url(publicGWServerUrl + "/api/v1/mesh-test-service-spring/hello/quarkus")
				.addHeader("X-Version","v999")
				.get()
				.build();
		try (Response response = okHttpClient.newCall(request).execute()) {
			assertEquals(200, response.code());
			String jsonResponse = response.body().string();
			TraceResponse traceResponse = new Gson().fromJson(jsonResponse, TraceResponse.class);
			assertEquals("mesh-test-service-quarkus", traceResponse.getFamilyName());
			assertEquals("v999", traceResponse.getXversion());
		}
	}

	@Test
	public void testRouteToQuarkusService() throws IOException {
		Request request = new Request.Builder()
				.url(publicGWServerUrl + "/api/v1/mesh-test-service-spring/hello/quarkus")
				.get()
				.build();
		try (Response response = okHttpClient.newCall(request).execute()) {
			assertEquals(200, response.code());
			String jsonResponse = response.body().string();
			TraceResponse traceResponse = new Gson().fromJson(jsonResponse, TraceResponse.class);
			assertEquals("mesh-test-service-quarkus", traceResponse.getFamilyName());
		}
	}

	@Test
	public void testCheckTrace() throws Exception {
		testCheckTrace("mesh-test-service-go:8080/api/v1/mesh-test-service-go/hello");
		testCheckTrace("mesh-test-service-go:1234/api/v1/mesh-test-service-go/1234/hello");
	}

	private void testCheckTrace(String url) throws Exception {
		log.info("testCheckTrace, url: {}", url);
		Request request = new Request.Builder()
				.url(publicGWServerUrl + "/api/v1/mesh-test-service-spring/spring/proxy?url=" + url)
				.get()
				.build();
		try (Response response = okHttpClient.newCall(request).execute()) {
			assertEquals(200, response.code());
			String jsonResponse = response.body().string();
			log.info("traceResponse: {}", jsonResponse);
		}
	}
}
