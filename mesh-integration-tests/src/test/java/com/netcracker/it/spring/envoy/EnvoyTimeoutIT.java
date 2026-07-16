package com.netcracker.it.spring.envoy;

import com.netcracker.cloud.junit.cloudcore.extension.annotations.EnableExtension;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Response;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.net.URL;
import java.util.Map;

import static com.netcracker.it.spring.envoy.Paths.API_V_1_MESH_TEST_SERVICE_SPRING;
import static org.junit.jupiter.api.Assertions.*;

@EnableExtension
@Slf4j
@Tag("EnvoyFilter")
class EnvoyTimeoutIT extends BaseTest {

    private static final String SLEEP_PATH = API_V_1_MESH_TEST_SERVICE_SPRING + "sleep?seconds=";
    private static final String SLEEP_VIA_TIMEOUT_ROUTE_PATH = API_V_1_MESH_TEST_SERVICE_SPRING + "timeout/sleep?seconds=";

    @Tag("slow")
    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("ingressAndMeshGateways")
    void testTimeoutOverriddenViaRoute(String gatewayName, URL baseUrl) throws IOException {
        testTimeBoundRequestViaTimeoutRoute(baseUrl, 20, (response, elapsedSec) -> {
            assertEquals(504, response.code(),
                    "Request exceeding route timeout should return 504 from %s".formatted(gatewayName));
            assertTrue(elapsedSec < 20,
                    String.format("Timeout should fire ~10s, got: %.1fs", elapsedSec));
        });
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("ingressUrls")
    void testRequestUnder120sSucceeds(String gatewayName, URL baseUrl) throws IOException {
        testTimeBoundRequest(baseUrl, 5, (response, elapsedSec) -> assertEquals(200, response.code()));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("ingressAndMeshGateways")
    void testTimeoutFiresNear120s(String gatewayName, URL baseUrl) throws IOException {
        Integer sleepSeconds = 130;
        testTimeBoundRequest(baseUrl, sleepSeconds, (response, elapsedSec) -> {
            int code = response.code();
            assertEquals(504, code, "Expected 504 Gateway Timeout from %s, got %d".formatted(code, gatewayName));
            assertAll("Timeout window",
                    () -> assertTrue(elapsedSec > 110, String.format("Too early: %.1fs", elapsedSec)),
                    () -> assertTrue(elapsedSec < 135, String.format("Too late: %.1fs", elapsedSec)));
        });
    }

    @FunctionalInterface
    interface ResponseAsserter {
        void test(Response response, double elapsedSec);
    }

    private void testTimeBoundRequest(URL baseUrl, Integer sleepSeconds, ResponseAsserter makeAssertions) throws IOException {
        testTimeBoundRequest(baseUrl, sleepSeconds, makeAssertions, SLEEP_PATH);
    }

    private void testTimeBoundRequestViaTimeoutRoute(URL baseUrl, Integer sleepSeconds, ResponseAsserter makeAssertions) throws IOException {
        testTimeBoundRequest(baseUrl, sleepSeconds, makeAssertions, SLEEP_VIA_TIMEOUT_ROUTE_PATH);
    }

    private void testTimeBoundRequest(URL baseUrl, Integer sleepSeconds, ResponseAsserter makeAssertions, String sleepPath) throws IOException {
        long start = System.currentTimeMillis();
        try (Response response = executeGetRequest(baseUrl, sleepPath + sleepSeconds, Map.of())) {
            double elapsedSec = (System.currentTimeMillis() - start) / 1000.0;
            makeAssertions.test(response, elapsedSec);
        }
    }
}
