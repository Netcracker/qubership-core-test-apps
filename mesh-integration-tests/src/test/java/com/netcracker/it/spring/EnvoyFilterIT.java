package com.netcracker.it.spring;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.*;
import com.netcracker.it.spring.model.TraceResponse;
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

    private static final String HELLO_PATH        = "api/v1/mesh-test-service-spring/hello";
    private static final String SLEEP_PATH        = "api/v1/mesh-test-service-spring/sleep";
    private static final String PROXY_HEADERS_PATH = "api/v1/mesh-test-service-spring/proxy-headers";
    private static final String INTERNAL_TARGET   = "internal-gateway-service:8080/trace-service/trace-test";

    @PortForward(serviceName = @Value(PUBLIC_GW_SERVICE_NAME))
    private static URL publicGWServerUrl;

    @BeforeAll
    public static void init() {
        assertNotNull(publicGWServerUrl, "publicGWServerUrl must be initialized");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 1. suppress_envoy_headers — gateway
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("1. Suppress x-envoy response headers (gateway)")
    class SuppressEnvoyHeaders {

        @Test
        @DisplayName("No x-envoy-* headers present in response")
        void noXEnvoyHeadersInResponse() throws IOException {
            Request request = new Request.Builder()
                    .url(publicGWServerUrl + HELLO_PATH)
                    .get()
                    .build();

            try (Response response = okHttpClient.newCall(request).execute()) {
                assertEquals(200, response.code());

                List<String> envoyHeaders = response.headers().names().stream()
                        .filter(name -> name.toLowerCase().startsWith("x-envoy"))
                        .toList();

                assertTrue(
                        envoyHeaders.isEmpty(),
                        "Found unexpected x-envoy headers: " + envoyHeaders
                );
            }
        }

        @Test
        @DisplayName("x-envoy-upstream-service-time is absent")
        void upstreamServiceTimeAbsent() throws IOException {
            assertResponseHeaderAbsent("x-envoy-upstream-service-time");
        }

        @Test
        @DisplayName("x-envoy-decorator-operation is absent")
        void decoratorOperationAbsent() throws IOException {
            assertResponseHeaderAbsent("x-envoy-decorator-operation");
        }

        @Test
        @DisplayName("x-envoy-peer-metadata is absent")
        void peerMetadataAbsent() throws IOException {
            assertResponseHeaderAbsent("x-envoy-peer-metadata");
        }

        @Test
        @DisplayName("x-envoy-peer-metadata-id is absent")
        void peerMetadataIdAbsent() throws IOException {
            assertResponseHeaderAbsent("x-envoy-peer-metadata-id");
        }

        private void assertResponseHeaderAbsent(String headerName) throws IOException {
            Request request = new Request.Builder()
                    .url(publicGWServerUrl + HELLO_PATH)
                    .get()
                    .build();

            try (Response response = okHttpClient.newCall(request).execute()) {
                assertEquals(200, response.code());
                assertNull(
                        response.header(headerName),
                        "Header '" + headerName + "' should be suppressed but was found"
                );
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. suppress_envoy_headers — waypoint
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("2. Suppress x-envoy response headers (waypoint)")
    class WaypointSuppressEnvoyHeaders {

        @Test
        @DisplayName("No x-envoy-* headers in response via waypoint")
        void noXEnvoyHeadersViaWaypoint() throws IOException {
            Request request = new Request.Builder()
                    .url(publicGWServerUrl + PROXY_HEADERS_PATH
                            + "?url=" + INTERNAL_TARGET)
                    .get()
                    .build();

            try (Response response = okHttpClient.newCall(request).execute()) {
                assertEquals(200, response.code());

                Map<String, List<String>> upstreamHeaders = new Gson().fromJson(
                        response.body().string(),
                        new TypeToken<Map<String, List<String>>>(){}.getType()
                );

                List<String> envoyHeaders = upstreamHeaders.keySet().stream()
                        .filter(h -> h.toLowerCase().startsWith("x-envoy"))
                        .toList();

                assertTrue(
                        envoyHeaders.isEmpty(),
                        "Found unexpected x-envoy headers via waypoint: " + envoyHeaders
                );
            }
        }

        @Test
        @DisplayName("x-envoy-decorator-operation absent via waypoint")
        void decoratorOperationAbsentViaWaypoint() throws IOException {
            Request request = new Request.Builder()
                    .url(publicGWServerUrl + PROXY_HEADERS_PATH
                            + "?url=" + INTERNAL_TARGET)
                    .get()
                    .build();

            try (Response response = okHttpClient.newCall(request).execute()) {
                assertEquals(200, response.code());

                Map<String, List<String>> upstreamHeaders = new Gson().fromJson(
                        response.body().string(),
                        new TypeToken<Map<String, List<String>>>(){}.getType()
                );

                assertNull(
                        upstreamHeaders.get("x-envoy-decorator-operation"),
                        "x-envoy-decorator-operation should be suppressed by waypoint"
                );
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. X-Request-ID not modified
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("3. X-Request-ID not modified by Envoy")
    class XRequestId {

        @Test
        @DisplayName("External X-Request-ID reaches upstream unchanged")
        void externalRequestIdPreserved() throws IOException {
            String originalId = UUID.randomUUID().toString();

            Request request = new Request.Builder()
                    .url(publicGWServerUrl + HELLO_PATH)
                    .addHeader("X-Request-ID", originalId)
                    .get()
                    .build();

            try (Response response = okHttpClient.newCall(request).execute()) {
                assertEquals(200, response.code());

                TraceResponse traceResponse = new Gson().fromJson(
                        response.body().string(), TraceResponse.class
                );

                String receivedId = traceResponse.getHeaders().get("x-request-id");

                assertEquals(
                        originalId,
                        receivedId,
                        "Envoy must not modify X-Request-ID supplied by the client"
                );
            }
        }

        @Test
        @DisplayName("Envoy does not generate X-Request-ID when absent")
        void noRequestIdNotGenerated() throws IOException {
            Request request = new Request.Builder()
                    .url(publicGWServerUrl + HELLO_PATH)
                    .get()
                    .build();

            try (Response response = okHttpClient.newCall(request).execute()) {
                assertEquals(200, response.code());

                TraceResponse traceResponse = new Gson().fromJson(
                        response.body().string(), TraceResponse.class
                );

                String generatedId = traceResponse.getHeaders().get("x-request-id");

                assertNull(
                        generatedId,
                        "Envoy should not generate X-Request-ID when absent, " +
                        "but upstream received: " + generatedId
                );
            }
        }

        @Test
        @DisplayName("Multiple requests each preserve their own unique X-Request-ID")
        void eachRequestPreservesItsOwnId() throws IOException {
            for (int i = 0; i < 5; i++) {
                String id = UUID.randomUUID().toString();

                Request request = new Request.Builder()
                        .url(publicGWServerUrl + HELLO_PATH)
                        .addHeader("X-Request-ID", id)
                        .get()
                        .build();

                try (Response response = okHttpClient.newCall(request).execute()) {
                    assertEquals(200, response.code());

                    TraceResponse traceResponse = new Gson().fromJson(
                            response.body().string(), TraceResponse.class
                    );

                    String received = traceResponse.getHeaders().get("x-request-id");
                    assertEquals(id, received, "Request " + i + ": ID was modified by Envoy");
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 4. Default timeout 120s
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("4. Default timeout 120s")
    class DefaultTimeout {

        @Test
        @DisplayName("Request with 5s delay succeeds (well under 120s)")
        void requestUnder120sSucceeds() throws IOException {
            Request request = new Request.Builder()
                    .url(publicGWServerUrl + SLEEP_PATH + "?seconds=5")
                    .get()
                    .build();

            try (Response response = okHttpClient.newCall(request).execute()) {
                assertEquals(200, response.code());
            }
        }

        @Test
        @Tag("slow")
        @DisplayName("Request with 130s delay gets 504 (timeout fires ~120s)")
        void requestOver120sGets504() throws IOException {
            long start = System.currentTimeMillis();

            Request request = new Request.Builder()
                    .url(publicGWServerUrl + SLEEP_PATH + "?seconds=130")
                    .get()
                    .build();

            try (Response response = okHttpClient.newCall(request).execute()) {
                double elapsedSec = (System.currentTimeMillis() - start) / 1000.0;
                log.info("Response status={} after {:.1f}s", response.code(), elapsedSec);

                assertEquals(504, response.code(),
                        "Expected 504 Gateway Timeout, got " + response.code());

                assertTrue(elapsedSec < 125,
                        String.format("Gateway waited too long: %.1fs (expected ~120s)", elapsedSec));
            }
        }

        @Test
        @Tag("slow")
        @DisplayName("Timeout fires between 115s and 125s")
        void timeoutFiresNear120s() throws IOException {
            long start = System.currentTimeMillis();

            Request request = new Request.Builder()
                    .url(publicGWServerUrl + SLEEP_PATH + "?seconds=130")
                    .get()
                    .build();

            try (Response response = okHttpClient.newCall(request).execute()) {
                double elapsedSec = (System.currentTimeMillis() - start) / 1000.0;

                assertEquals(504, response.code(),
                        "Expected 504 Gateway Timeout, got " + response.code());

                assertAll(
                        "Timeout window",
                        () -> assertTrue(elapsedSec > 115,
                                String.format("Timeout fired too early: %.1fs (expected >115s)", elapsedSec)),
                        () -> assertTrue(elapsedSec < 125,
                                String.format("Timeout fired too late: %.1fs (expected <125s)", elapsedSec))
                );
            }
        }
    }
}