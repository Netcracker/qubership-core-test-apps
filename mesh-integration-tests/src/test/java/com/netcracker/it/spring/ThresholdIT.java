package com.netcracker.it.spring;

import com.netcracker.cloud.junit.cloudcore.extension.annotations.*;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.netcracker.it.spring.Const.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

@Slf4j
@EnableExtension
@SmokeTest
class ThresholdIT {

	@PortForward(serviceName = @Value(SERVICE_NAME), cloud = @Cloud(namespace = @Value(prop = ORIGIN_NAMESPACE_ENV_NAME)))
    private static URL springCompositeGWServerUrl;

	@PortForward(serviceName = @Value(PUBLIC_GW_SERVICE_NAME), cloud = @Cloud(namespace = @Value(prop = ORIGIN_NAMESPACE_ENV_NAME)))
    private static URL publicGWServerUrl;

    @PortForward(serviceName = @Value("egress-gateway"), cloud = @Cloud(namespace = @Value(prop = ORIGIN_NAMESPACE_ENV_NAME)))
    private URL egressGatewayUrl;

    private static final int REQUESTS_NUMBER = 6;
    private static final int LIMIT_VALUE = 2;

    private static final int SLEEP_DURATION_SECONDS = 12;
    private static final String SLEEP_DURATION_SECONDS_STRING = String.valueOf(SLEEP_DURATION_SECONDS);

    /**
     * Requests that get an upstream connection right away come back after ~SLEEP_DURATION_SECONDS, the
     * throttled ones wait for a free connection and come back after at least twice that, so the cutoff is
     * put in the middle to leave the same margin on both sides.
     */
    private static final long FAST_RESPONSE_CUTOFF_MILLIS = SLEEP_DURATION_SECONDS * 1500L;
    private static final int CONNECT_TIMEOUT_MILLIS = 30_000;
    private static final int START_ALIGN_TIMEOUT_MILLIS = 30_000;
    private static final int READ_TIMEOUT_MILLIS = SLEEP_DURATION_SECONDS * 1000 * (REQUESTS_NUMBER / LIMIT_VALUE) + 60_000;

    /**
     * Route bound to a cluster used by this test only: circuit breaker settings belong to the cluster and
     * control-plane drops them as soon as the same cluster is registered again without a circuitBreaker
     * section, which the service does on every startup for its own routes.
     */
    private static final String CONNECTION_LIMITED_SLEEP_PATH =
            "api/v1/mesh-test-service-spring/connection-limit/sleep?seconds=" + SLEEP_DURATION_SECONDS_STRING;

    /**
     * The quarkus composite service is reached from inside the mesh, through the spring service: an address
     * forwarded from outside the cluster skips the mesh proxies in Istio mesh mode and the composite route is
     * never applied there.
     */
    private static final String QUARKUS_SLEEP_VIA_SPRING_PROXY =
            "api/v1/mesh-test-service-spring/spring/proxy?url=" + QUARKUS_SERVICE_NAME
                    + ":8080/api/v1/mesh-test-service-quarkus/sleep?seconds=" + SLEEP_DURATION_SECONDS_STRING;

    @BeforeAll
    void init() {
        assertNotNull(springCompositeGWServerUrl);
        assertNotNull(publicGWServerUrl);
        assertNotNull(egressGatewayUrl);
    }

    @Test
    void testLoadEnvoyWithConcurrency1AndConnectionLimit() throws Exception {
        // connection limit comes from the control-plane circuitBreaker, Istio mesh has no equivalent configuration
        assumeFalse(isIstioMesh(), "Connection limit is configured in Core mesh only");
        //load envoy with concurrency=1 and connection limited cluster
        loadEnvoy(springCompositeGWServerUrl.toString() + CONNECTION_LIMITED_SLEEP_PATH, true);
    }

    @Test
    void testLoadEnvoyWithAverageConfig() throws Exception {
        //load envoy with average concurrency and without limit
        loadEnvoy(publicGWServerUrl.toString() + QUARKUS_SLEEP_VIA_SPRING_PROXY, false);
    }

    @Test
    void testLoadEnvoyWithConcurrency1WithoutConnectionLimit() throws Exception {
        //load envoy with concurrency=1 and without limit
        loadEnvoy(egressGatewayUrl.toString() + "api/v1/mesh-test-service-quarkus/sleep?seconds=" + SLEEP_DURATION_SECONDS_STRING, false);
    }

//    @Test//FLOATING SCENERY
//    public void testLoadEnvoyWithConnectionLimit() throws Exception {
//        //load envoy with average concurrency and with limit
//        loadEnvoy(compositeGWServerUrl.toString() + "api/v1/mesh-test-service-spring/sleep?seconds=" + SLEEP_DURATION_SECONDS_STRING, false);
//    }

    private void loadEnvoy(String requestUrl, boolean shouldSomeRequestsGoOutOfTimeout) throws ExecutionException, InterruptedException {
        ExecutorService executorService = Executors.newFixedThreadPool(REQUESTS_NUMBER);
        CountDownLatch startLine = new CountDownLatch(REQUESTS_NUMBER);
        List<Long> responsesTimes = Collections.synchronizedList(new ArrayList<>());
        List<Integer> responsesCodes = Collections.synchronizedList(new ArrayList<>());
        List<Runnable> reqs = new ArrayList<>();

        for (int i = 0; i < REQUESTS_NUMBER; i++) {
            reqs.add(() -> {
                try {
                    // all threads leave at once, so response times are comparable to each other
                    startLine.countDown();
                    if (!startLine.await(START_ALIGN_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                        throw new IllegalStateException("Requests could not be started simultaneously");
                    }
                    long requestStart = System.currentTimeMillis();
                    URL url = new URI(requestUrl).toURL();
                    HttpURLConnection httpURLConnection1 = (HttpURLConnection) url.openConnection();
                    httpURLConnection1.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
                    httpURLConnection1.setReadTimeout(READ_TIMEOUT_MILLIS);
                    try {
                        int responseCode = httpURLConnection1.getResponseCode();
                        log.info("response_code:" + responseCode);
                        responsesCodes.add(responseCode);
                        responsesTimes.add(System.currentTimeMillis() - requestStart);
                    } finally {
                        httpURLConnection1.disconnect();
                    }
                } catch (IOException | URISyntaxException e) {
                    throw new RuntimeException(e);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            });
        }
        List<? extends Future<?>> futures = reqs.stream().map(executorService::submit).toList();
        try {
            for (Future<?> future : futures)
            {
                future.get();
            }
        } finally {
            executorService.shutdownNow();
        }

        assertTrue(responsesCodes.stream().allMatch(code -> code == 200),
                "All requests to " + requestUrl + " must succeed, got response codes: " + responsesCodes);

        int match = 0, noMatch = 0;
        for (Long responseTime : responsesTimes) {
            if (responseTime <= FAST_RESPONSE_CUTOFF_MILLIS) {
                match++;
            } else {
                noMatch++;
            }
        }
        String details = String.format(
                "url=%s, response times (ms)=%s, cutoff=%d ms", requestUrl, responsesTimes, FAST_RESPONSE_CUTOFF_MILLIS);
        if (shouldSomeRequestsGoOutOfTimeout) {
            assertEquals(LIMIT_VALUE, match, "Only " + LIMIT_VALUE + " requests may pass the connection limit at once: " + details);
            assertEquals(REQUESTS_NUMBER  - LIMIT_VALUE, noMatch, "Requests above the connection limit must wait: " + details);
        } else {
            assertEquals(REQUESTS_NUMBER, match, "Unlimited cluster must serve all requests at once: " + details);
            assertEquals(0, noMatch, "No request may be throttled on an unlimited cluster: " + details);
        }
    }
}
