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

import java.io.IOException;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.netcracker.it.common.HttpClient.okHttpClient;
import static com.netcracker.it.spring.Const.PUBLIC_GW_SERVICE_NAME;
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

    @BeforeAll
    public static void init() {
        assertNotNull(publicGWServerUrl, "publicGWServerUrl must be initialized");
    }

    // ── 1. X-Request-ID ──────────────────────────────────────────────────────

    @Test
    void testExternalRequestIdPreserved() throws IOException {
        String originalId = UUID.randomUUID().toString();
        Request request = new Request.Builder()
                .url(publicGWServerUrl + REQUEST_HEADERS_PATH)
                .addHeader("X-Request-ID", originalId)
                .get().build();
        try (Response response = okHttpClient.newCall(request).execute()) {
        String body = response.body().string();
        int code = response.code();
        okhttp3.Headers responseHeaders = response.headers();
        
        log.info("Response headers: {}, body: {}", responseHeaders, body);
        assertEquals(200, code);
        Map<String, String> headers = new Gson().fromJson(
                body,
                new TypeToken<Map<String, String>>(){}.getType());
        assertEquals(originalId, headers.get("x-request-id"),
                "Envoy must not modify X-Request-ID supplied by the client");
        }
    }

    @Test
    void testRequestIGeneratedWhenAbsent() throws IOException {
        Request request = new Request.Builder()
                .url(publicGWServerUrl + REQUEST_HEADERS_PATH)
                .get().build();
        try (Response response = okHttpClient.newCall(request).execute()) {
            String body = response.body().string();
            int code = response.code();
            okhttp3.Headers responseHeaders = response.headers();
            
            log.info("Response headers: {}, body: {}", responseHeaders, body);
            assertEquals(200, code);
            Map<String, String> headers = new Gson().fromJson(
                    body,
                    new TypeToken<Map<String, String>>(){}.getType());
            assertNotNull(headers.get("x-request-id"),
                    "Envoy should generate X-Request-ID when absent");
        }
    }

    @Test
    void testEachRequestPreservesItsOwnId() throws IOException {
        for (int i = 0; i < 5; i++) {
            String id = UUID.randomUUID().toString();
            Request request = new Request.Builder()
                    .url(publicGWServerUrl + REQUEST_HEADERS_PATH)
                    .addHeader("X-Request-ID", id)
                    .get().build();
            try (Response response = okHttpClient.newCall(request).execute()) {
                String body = response.body().string();
                int code = response.code();
                okhttp3.Headers responseHeaders = response.headers();
                
                log.info("Response headers: {}, body: {}", responseHeaders, body);
                assertEquals(200, code);
                Map<String, String> headers = new Gson().fromJson(
                        body,
                        new TypeToken<Map<String, String>>(){}.getType());
                assertEquals(id, headers.get("x-request-id"),
                        "Request " + i + ": ID was modified by Envoy");
            }
        }
    }


    // ── 2. suppress headers — gateway ────────────────────────────────────────

    @Test
    @EnabledIfEnvironmentVariable(named = "SERVICE_MESH_TYPE", matches = "Istio")
    void testNoXEnvoyHeadersInResponse() throws IOException {
        Request request = new Request.Builder()
                .url(publicGWServerUrl + HELLO_PATH)
                .get().build();
        try (Response response = okHttpClient.newCall(request).execute()) {
            String body = response.body().string();
            int code = response.code();
            okhttp3.Headers responseHeaders = response.headers();

            log.info("Response headers: {}, body: {}", responseHeaders, body);
            assertEquals(200, code);
            List<String> envoyHeaders = responseHeaders.names().stream()
                    .filter(name -> name.toLowerCase().startsWith("x-envoy"))
                    .toList();
            assertTrue(envoyHeaders.isEmpty(),
                    "Found unexpected x-envoy headers: " + envoyHeaders);
        }
    }

    @Test
    void testXEnvoyUpstreamServiceTimeAbsent() throws IOException {
        assertResponseHeaderAbsent("server");
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

    @Test
    void testRequestUnder120sSucceeds() throws IOException {
        Request request = new Request.Builder()
                .url(publicGWServerUrl + SLEEP_PATH + "?seconds=5")
                .get().build();
        try (Response response = okHttpClient.newCall(request).execute()) {
            String body = response.body().string();
            int code = response.code();
            okhttp3.Headers responseHeaders = response.headers();

            log.info("Response headers: {}, body: {}", responseHeaders, body);
            assertEquals(200, code);
        }
    }

    @Test
    @Tag("slow")
    void testRequestOver120sGets504() throws IOException {
        long start = System.currentTimeMillis();
        Request request = new Request.Builder()
                .url(publicGWServerUrl + SLEEP_PATH + "?seconds=130")
                .get().build();
        try (Response response = okHttpClient.newCall(request).execute()) {
            String body = response.body().string();
            int code = response.code();
            okhttp3.Headers responseHeaders = response.headers();

            log.info("Response headers: {}, body: {}", responseHeaders, body);
            double elapsedSec = (System.currentTimeMillis() - start) / 1000.0;
            assertEquals(504, code,
                    "Expected 504 Gateway Timeout, got " + code);
            assertTrue(elapsedSec < 125,
                    String.format("Gateway waited too long: %.1fs", elapsedSec));
        }
    }

    @Test
    @Tag("slow")
    void testTimeoutFiresNear120s() throws IOException {
        long start = System.currentTimeMillis();
        Request request = new Request.Builder()
                .url(publicGWServerUrl + SLEEP_PATH + "?seconds=130")
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
                    () -> assertTrue(elapsedSec > 115,
                            String.format("Too early: %.1fs", elapsedSec)),
                    () -> assertTrue(elapsedSec < 125,
                            String.format("Too late: %.1fs", elapsedSec)));
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void assertResponseHeaderAbsent(String headerName) throws IOException {
        Request request = new Request.Builder()
                .url(publicGWServerUrl + HELLO_PATH)
                .get().build();
        try (Response response = okHttpClient.newCall(request).execute()) {
            String body = response.body().string();
            int code = response.code();
            okhttp3.Headers responseHeaders = response.headers();

            log.info("Response headers: {}, body: {}", responseHeaders, body);
            assertEquals(200, code);
            assertNull(responseHeaders.get(headerName),
                    "Header '" + headerName + "' should be suppressed but was found");
        }
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