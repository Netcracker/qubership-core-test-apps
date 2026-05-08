package com.netcracker.it.spring;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.*;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.*;

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

    private static final String HELLO_PATH         = "api/v1/mesh-test-service-spring/hello";
    private static final String SLEEP_PATH         = "api/v1/mesh-test-service-spring/sleep";
    private static final String PROXY_HEADERS_PATH = "api/v1/mesh-test-service-spring/proxy-headers";
    private static final String INTERNAL_TARGET    = "internal-gateway-service:8080/trace-service/trace-test";
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
            log.info("Response headers: {}, body: {}", response.headers(), response.body().string());
            assertEquals(200, response.code());
            // endpoint returns flat Map<String,String> of request headers
            Map<String, String> headers = new Gson().fromJson(
                    response.body().string(),
                    new TypeToken<Map<String, String>>(){}.getType());
            assertEquals(originalId, headers.get("x-request-id"),
                    "Envoy must not modify X-Request-ID supplied by the client");
        }
    }

    @Test
    void testRequestIdNotGeneratedWhenAbsent() throws IOException {
        Request request = new Request.Builder()
                .url(publicGWServerUrl + REQUEST_HEADERS_PATH)
                .get().build();
        try (Response response = okHttpClient.newCall(request).execute()) {
            log.info("Response headers: {}, body: {}", response.headers(), response.body().string());
            assertEquals(200, response.code());
            Map<String, String> headers = new Gson().fromJson(
                    response.body().string(),
                    new TypeToken<Map<String, String>>(){}.getType());
            assertNull(headers.get("x-request-id"),
                    "Envoy should not generate X-Request-ID when absent");
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
                log.info("Response headers: {}, body: {}", response.headers(), response.body().string());
                assertEquals(200, response.code());
                Map<String, String> headers = new Gson().fromJson(
                        response.body().string(),
                        new TypeToken<Map<String, String>>(){}.getType());
                assertEquals(id, headers.get("x-request-id"),
                        "Request " + i + ": ID was modified by Envoy");
            }
        }
    }


    // ── 2. suppress headers — gateway ────────────────────────────────────────

    @Test
    void testNoXEnvoyHeadersInResponse() throws IOException {
        Request request = new Request.Builder()
                .url(publicGWServerUrl + HELLO_PATH)
                .get().build();
        try (Response response = okHttpClient.newCall(request).execute()) {
            log.info("Response headers: {}, body: {}", response.headers(), response.body().string());
            assertEquals(200, response.code());
            List<String> envoyHeaders = response.headers().names().stream()
                    .filter(name -> name.toLowerCase().startsWith("x-envoy"))
                    .toList();
            assertTrue(envoyHeaders.isEmpty(),
                    "Found unexpected x-envoy headers: " + envoyHeaders);
        }
    }

    @Test
    void testXEnvoyUpstreamServiceTimeAbsent() throws IOException {
        assertResponseHeaderAbsent("x-envoy-upstream-service-time");
    }

    @Test
    void testXEnvoyDecoratorOperationAbsent() throws IOException {
        assertResponseHeaderAbsent("x-envoy-decorator-operation");
    }

    @Test
    void testXEnvoyPeerMetadataAbsent() throws IOException {
        assertResponseHeaderAbsent("x-envoy-peer-metadata");
    }

    @Test
    void testXEnvoyPeerMetadataIdAbsent() throws IOException {
        assertResponseHeaderAbsent("x-envoy-peer-metadata-id");
    }

    // ── 3. suppress headers — waypoint ───────────────────────────────────────

    @Test
    void testNoXEnvoyHeadersViaWaypoint() throws IOException {
        Request request = new Request.Builder()
                .url(publicGWServerUrl + PROXY_HEADERS_PATH + "?url=" + INTERNAL_TARGET)
                .get().build();
        try (Response response = okHttpClient.newCall(request).execute()) {
            log.info("Response headers: {}, body: {}", response.headers(), response.body().string());
            assertEquals(200, response.code());
            Map<String, List<String>> upstreamHeaders = new Gson().fromJson(
                    response.body().string(),
                    new TypeToken<Map<String, List<String>>>(){}.getType());
            List<String> envoyHeaders = upstreamHeaders.keySet().stream()
                    .filter(h -> h.toLowerCase().startsWith("x-envoy"))
                    .toList();
            assertTrue(envoyHeaders.isEmpty(),
                    "Found unexpected x-envoy headers via waypoint: " + envoyHeaders);
        }
    }

    @Test
    void testXEnvoyDecoratorOperationAbsentViaWaypoint() throws IOException {
        Request request = new Request.Builder()
                .url(publicGWServerUrl + PROXY_HEADERS_PATH + "?url=" + INTERNAL_TARGET)
                .get().build();
        try (Response response = okHttpClient.newCall(request).execute()) {
            log.info("Response headers: {}, body: {}", response.headers(), response.body().string());
            assertEquals(200, response.code());
            Map<String, List<String>> upstreamHeaders = new Gson().fromJson(
                    response.body().string(),
                    new TypeToken<Map<String, List<String>>>(){}.getType());
            assertNull(upstreamHeaders.get("x-envoy-decorator-operation"),
                    "x-envoy-decorator-operation should be suppressed by waypoint");
        }
    }

    // ── 4. Timeout ───────────────────────────────────────────────────────────

    @Test
    void testRequestUnder120sSucceeds() throws IOException {
        Request request = new Request.Builder()
                .url(publicGWServerUrl + SLEEP_PATH + "?seconds=5")
                .get().build();
        try (Response response = okHttpClient.newCall(request).execute()) {
            log.info("Response headers: {}, body: {}", response.headers(), response.body().string());
            assertEquals(200, response.code());
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
            log.info("Response headers: {}, body: {}", response.headers(), response.body().string());
            double elapsedSec = (System.currentTimeMillis() - start) / 1000.0;
            assertEquals(504, response.code(),
                    "Expected 504 Gateway Timeout, got " + response.code());
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
            log.info("Response headers: {}, body: {}", response.headers(), response.body().string());
            double elapsedSec = (System.currentTimeMillis() - start) / 1000.0;
            assertEquals(504, response.code(),
                    "Expected 504 Gateway Timeout, got " + response.code());
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
            log.info("Response headers: {}, body: {}", response.headers(), response.body().string());
            assertEquals(200, response.code());
            assertNull(response.header(headerName),
                    "Header '" + headerName + "' should be suppressed but was found");
        }
    }
}