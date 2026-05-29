package com.netcracker.it.spring;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.*;
import com.netcracker.it.spring.model.ProxyResponse;

import lombok.extern.slf4j.Slf4j;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static com.netcracker.it.common.HttpClient.okHttpClient;
import static com.netcracker.it.spring.Const.*;
import static org.junit.jupiter.api.Assertions.*;

@EnableExtension
@Slf4j
@Tag("EnvoyFilter")
public class EnvoyFilterIT {

    private static final String HELLO_PATH           = "api/v1/mesh-test-service-spring/hello";
    private static final String SLEEP_PATH           = "api/v1/mesh-test-service-spring/sleep";
    private static final String PROXY_HEADERS_PATH   = "api/v1/mesh-test-service-spring/proxy-headers";
    private static final String INTERNAL_SLEEP       = "internal-gateway-service:8080/api/v1/mesh-test-service-spring/sleep?seconds=30";
    private static final String INTERNAL_HELLO       = "internal-gateway-service:8080/api/v1/mesh-test-service-spring/hello";
    private static final String REQUEST_HEADERS_PATH = "api/v1/mesh-test-service-spring/request-headers";

    @PortForward(serviceName = @Value(PUBLIC_GW_SERVICE_NAME))
    private static URL publicGWServerUrl;

    @PortForward(serviceName = @Value(PRIVATE_GW_SERVICE_NAME))
    private static URL privateGWServerUrl;

    @BeforeAll
    public static void init() {
        assertNotNull(publicGWServerUrl,   "publicGWServerUrl must be initialized");
        assertNotNull(privateGWServerUrl,  "privateGWServerUrl must be initialized");
    }

    static Stream<Arguments> gatewayUrls() {
        return Stream.of(
                Arguments.of("public-gateway",   publicGWServerUrl),
                Arguments.of("private-gateway",  privateGWServerUrl)
        );
    }

    // ── 1. X-Request-ID ──────────────────────────────────────────────────────

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("gatewayUrls")
    void testExternalRequestIdPreserved(String gatewayName, URL baseUrl) throws IOException {
        String originalId = UUID.randomUUID().toString();
        Map<String, String> headers = executeAndGetBodyHeaders(
                baseUrl, REQUEST_HEADERS_PATH, Map.of("X-Request-ID", originalId));
        assertEquals(originalId, headers.get("x-request-id"),
                gatewayName + ": Envoy must not modify X-Request-ID supplied by the client");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "SERVICE_MESH_TYPE", matches = "Istio")
    void testExternalRequestIdPreservedThroughWaypoint() throws IOException {
        ProxyResponse proxy = fetchProxyResponse(INTERNAL_HELLO);
        assertNotNull(proxy.getHeaders().get("x-request-id"),
                "Waypoint must not strip x-request-id from response");
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("gatewayUrls")
    void testRequestIdGeneratedWhenAbsent(String gatewayName, URL baseUrl) throws IOException {
        Map<String, String> headers = executeAndGetBodyHeaders(
                baseUrl, REQUEST_HEADERS_PATH, Collections.emptyMap());
        assertNotNull(headers.get("x-request-id"),
                gatewayName + " :Envoy should generate X-Request-ID when absent");
    }

    // ── 2. suppress headers — gateway ────────────────────────────────────────

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("gatewayUrls")
    @EnabledIfEnvironmentVariable(named = "SERVICE_MESH_TYPE", matches = "Istio")
    void testNoXEnvoyHeadersInResponse(String gatewayName, URL baseUrl) throws IOException {
        okhttp3.Headers responseHeaders = executeAndGetResponseHeaders( 
                baseUrl, HELLO_PATH, Collections.emptyMap());
        List<String> envoyHeaders = responseHeaders.names().stream()
                .filter(name -> name.toLowerCase().startsWith("x-envoy"))
                .toList();
        assertTrue(envoyHeaders.isEmpty(),
                "Found unexpected x-envoy headers: " + envoyHeaders);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("gatewayUrls")
    void testServerHeaderAbsent(String gatewayName, URL baseUrl) throws IOException {
        assertResponseHeaderAbsent(baseUrl, HELLO_PATH, "server");
    }


    // ── 3. override timeouts — waypoint ───────────────────────────────────────

    @Test
    @EnabledIfEnvironmentVariable(named = "SERVICE_MESH_TYPE", matches = "Istio")
    void testRequestThroughWaypointSucceeds() throws IOException {
        long start = System.currentTimeMillis();
        ProxyResponse proxy = fetchProxyResponse(INTERNAL_HELLO);
        double elapsed = (System.currentTimeMillis() - start) / 1000.0;
        assertEquals(200, proxy.getStatus(),
                "Request through waypoint should succeed");
        assertTrue(elapsed < 120,
                String.format("Request should complete well under timeout: %.1fs", elapsed));
    }

    @Test
    @Tag("slow")
    @EnabledIfEnvironmentVariable(named = "SERVICE_MESH_TYPE", matches = "Istio")
    void testTimeoutOverriddenViaHttpRoute() throws IOException {
        long start = System.currentTimeMillis();
        ProxyResponse proxy = fetchProxyResponse(INTERNAL_SLEEP);
        double elapsed = (System.currentTimeMillis() - start) / 1000.0;

        assertEquals(504, proxy.getStatus(),
                "Request exceeding route timeout should return 504 from waypoint");
        assertTrue(elapsed < 20,
                String.format("Timeout should fire ~10s via waypoint, got: %.1fs", elapsed));
    }

    // ── 4. Timeout ───────────────────────────────────────────────────────────

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("gatewayUrls")
    void testRequestUnder120sSucceeds(String gatewayName, URL baseUrl) throws IOException {
        Request request = new Request.Builder()
                .url(baseUrl + SLEEP_PATH + "?seconds=5")
                .get().build();
        try (Response response = okHttpClient.newCall(request).execute()) {
            log.info("Response headers: {}, body: {}", response.headers(), response.body().string());
            assertEquals(200, response.code());
        }
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("gatewayUrls")
    void testTimeoutFiresNear120s(String gatewayName, URL baseUrl) throws IOException {
        long start = System.currentTimeMillis();
        Request request = new Request.Builder()
                .url(baseUrl + SLEEP_PATH + "?seconds=130")
                .get().build();
        try (Response response = okHttpClient.newCall(request).execute()) {
            String body = response.body().string();
            int code = response.code();
            okhttp3.Headers responseHeaders = response.headers();

            log.info("Response headers: {}, body: {}", responseHeaders, body);
            double elapsedSec = (System.currentTimeMillis() - start) / 1000.0;
            assertEquals(504, code,
                    "Expected 504 Gateway Timeout, got " + code);
            assertAll("Timeout window",
                    () -> assertTrue(elapsedSec > 110,
                            String.format("Too early: %.1fs", elapsedSec)),
                    () -> assertTrue(elapsedSec < 135,
                            String.format("Too late: %.1fs", elapsedSec)));
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * Issues a GET to {@code path} with the given request headers, asserts a 200 response,
     * logs the response, and returns the JSON-decoded body as a Map (used by the
     * request-headers echo endpoint).
     */
    private Map<String, String> executeAndGetBodyHeaders(URL baseUrl, String path,
                                                         Map<String, String> requestHeaders) throws IOException {
        Request.Builder builder = new Request.Builder()
                .url(baseUrl + path)
                .get();
        requestHeaders.forEach(builder::addHeader);
        try (Response response = okHttpClient.newCall(builder.build()).execute()) {
            String body = response.body().string();
            int code = response.code();
            log.info("Response headers: {}, body: {}", response.headers(), body);
            assertEquals(200, code);
            return new Gson().fromJson(body, new TypeToken<Map<String, String>>(){}.getType());
        }
    }

    /**
     * Issues a GET to {@code path} with the given request headers, asserts a 200 response,
     * and returns the raw response headers (used when the assertion is about headers
     * the gateway/envoy emit, not the echoed request body).
     */
    private okhttp3.Headers executeAndGetResponseHeaders(URL baseUrl, String path,
                                                         Map<String, String> requestHeaders) throws IOException {
        Request.Builder builder = new Request.Builder()
                .url(baseUrl + path)
                .get();
        requestHeaders.forEach(builder::addHeader);
        try (Response response = okHttpClient.newCall(builder.build()).execute()) {
            String body = response.body().string();
            int code = response.code();
            okhttp3.Headers responseHeaders = response.headers();
            log.info("Response headers: {}, body: {}", responseHeaders, body);
            assertEquals(200, code);
            return responseHeaders;
        }
    }

    private void assertResponseHeaderAbsent(URL baseUrl, String path, String headerName) throws IOException {
        okhttp3.Headers responseHeaders = executeAndGetResponseHeaders(baseUrl, path, Collections.emptyMap());
        assertNull(responseHeaders.get(headerName),
                "Header '" + headerName + "' should be suppressed but was found");
    }

    private ProxyResponse fetchProxyResponse(String targetUrl) throws IOException {
        okhttp3.HttpUrl url = okhttp3.HttpUrl.parse(publicGWServerUrl + PROXY_HEADERS_PATH)
                .newBuilder()
                .addQueryParameter("url", targetUrl)
                .build();

        Request request = new Request.Builder()
                .url(url)
                .get().build();

        try (Response response = okHttpClient.newCall(request).execute()) {
            String body = response.body().string();
            log.info("proxy-headers response code={} body={}", response.code(), body);
            assertEquals(200, response.code());
            return new Gson().fromJson(body, ProxyResponse.class);
        }
    }
}
