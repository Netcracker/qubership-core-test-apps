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
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.netcracker.it.spring.Const.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@Slf4j
@EnableExtension
@SmokeTest
class ThresholdIT {

	@PortForward(serviceName = @Value(SERVICE_NAME), cloud = @Cloud(namespace = @Value(prop = ORIGIN_NAMESPACE_ENV_NAME)))
    private static URL springCompositeGWServerUrl;

	@PortForward(serviceName = @Value(QUARKUS_SERVICE_NAME), cloud = @Cloud(namespace = @Value(prop = ORIGIN_NAMESPACE_ENV_NAME)))
    private static URL quarkusCompositeGWServerUrl;

    @PortForward(serviceName = @Value("egress-gateway"), cloud = @Cloud(namespace = @Value(prop = ORIGIN_NAMESPACE_ENV_NAME)))
    private URL egressGatewayUrl;

    private static final int REQUESTS_NUMBER = 6;
    private static final int LIMIT_VALUE = 2;

    private static final int SLEEP_DURATION_SECONDS = 12;
    private static final String SLEEP_DURATION_SECONDS_STRING = String.valueOf(SLEEP_DURATION_SECONDS);
    private static final int TIMEOUT_SECONDS = 10;

    @BeforeAll
    void init() {
        assertNotNull(springCompositeGWServerUrl);
        assertNotNull(quarkusCompositeGWServerUrl);
        assertNotNull(egressGatewayUrl);
    }

    @Test
    void testLoadEnvoyWithConcurrency1AndConnectionLimit() throws Exception {
        //load envoy with concurrency=1 and connection limited cluster
        loadEnvoy(springCompositeGWServerUrl.toString() + "api/v1/mesh-test-service-spring/sleep?seconds=" + SLEEP_DURATION_SECONDS_STRING, true);
    }

    @Test
    void testLoadEnvoyWithAverageConfig() throws Exception {
        //load envoy with average concurrency and without limit
        loadEnvoy(quarkusCompositeGWServerUrl.toString() + "api/v1/mesh-test-service-quarkus/sleep?seconds=" + SLEEP_DURATION_SECONDS_STRING, false);
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
        List<Runnable> reqs = new ArrayList<>();

        Long before = System.currentTimeMillis();
        Long afterWithTimeout = before + SLEEP_DURATION_SECONDS*1000 + TIMEOUT_SECONDS*1000;
        int match = 0, noMatch = 0;

        ArrayList<Long> responsesTimes = new ArrayList<>();

        for (int i = 0; i < REQUESTS_NUMBER; i++) {
            reqs.add(() -> {
                try {
                    URL url = new URI(requestUrl).toURL();
                    HttpURLConnection httpURLConnection1 = (HttpURLConnection) url.openConnection();
                    httpURLConnection1.connect();
                    log.info("response_code:" + httpURLConnection1.getResponseCode());
                    responsesTimes.add(System.currentTimeMillis());
                } catch (IOException | URISyntaxException e) {
                    throw new RuntimeException(e);
                }
            });
        }
        List<? extends Future<?>> futures = reqs.stream().map(executorService::submit).toList();
        for (Future<?> future : futures)
        {
            future.get();
        }
        executorService.shutdownNow();

        for (Long responseTime : responsesTimes) {
            if (responseTime <= afterWithTimeout) {
                match++;
            } else {
                noMatch++;
            }
        }
        if (shouldSomeRequestsGoOutOfTimeout) {
            assertEquals(LIMIT_VALUE, match);
            assertEquals(REQUESTS_NUMBER  - LIMIT_VALUE, noMatch);
        } else {
            assertEquals(REQUESTS_NUMBER, match);
            assertEquals(0, noMatch);
        }
    }
}