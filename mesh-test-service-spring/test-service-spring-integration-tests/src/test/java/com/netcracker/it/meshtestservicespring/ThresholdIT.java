package com.netcracker.it.meshtestservicespring;

import com.netcracker.cloud.junit.cloudcore.extension.annotations.*;
import com.netcracker.cloud.junit.cloudcore.extension.service.PortForwardService;

import lombok.extern.slf4j.Slf4j;
import okhttp3.Request;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import static com.netcracker.it.meshtestservicespring.Const.*;
import static org.junit.jupiter.api.Assertions.*;

@Slf4j
@EnableExtension
@SmokeTest
public class ThresholdIT {

	@PortForward(serviceName = @Value(INTERNAL_GW_SERVICE_NAME), cloud = @Cloud(namespace = @Value(prop = ORIGIN_NAMESPACE_ENV_NAME)))
    private static URL internalGWServerUrl;

	@PortForward(serviceName = @Value(PUBLIC_GW_SERVICE_NAME), cloud = @Cloud(namespace = @Value(prop = ORIGIN_NAMESPACE_ENV_NAME)))
    private URL publicGatewayUrl;

	@PortForward(serviceName = @Value("egress-gateway"), cloud = @Cloud(namespace = @Value(prop = ORIGIN_NAMESPACE_ENV_NAME)))
    private URL egressGatewayUrl;

	@PortForward(serviceName = @Value(SERVICE_NAME), cloud = @Cloud(namespace = @Value(prop = ORIGIN_NAMESPACE_ENV_NAME)))
    private static URL compositeGWServerUrl;

	@PortForward(serviceName = @Value(QUARKUS_SERVICE_NAME), cloud = @Cloud(namespace = @Value(prop = ORIGIN_NAMESPACE_ENV_NAME)))
    private static URL quarkusCompositeGWServerUrl;

	@Cloud
    static PortForwardService portForwardService;

    private static final int REQUESTS_NUMBER = 6;
    private static final int LIMIT_VALUE = 2;

    private static final int SLEEP_DURATION_SECONDS = 12;
    private static final String SLEEP_DURATION_SECONDS_STRING = String.valueOf(SLEEP_DURATION_SECONDS);
    private static final int TIMEOUT_SECONDS = 10;

    @BeforeAll
    public void init() throws Exception {
        assertNotNull(publicGatewayUrl);
        assertNotNull(internalGWServerUrl);
        assertNotNull(egressGatewayUrl);
        assertNotNull(compositeGWServerUrl);
    }

    @Test
    public void testLoadEnvoyWithConcurrency1AndConnectionLimit() throws Exception {
        //load envoy with concurrency=1 and connection limited cluster
        loadEnvoy(egressGatewayUrl.toString() + "api/v1/mesh-test-service-spring/sleep?seconds=" + SLEEP_DURATION_SECONDS_STRING, true);
    }

    @Test
    public void testLoadEnvoyWithAverageConfig() throws Exception {
        //load envoy with average concurrency and without limit
        loadEnvoy(quarkusCompositeGWServerUrl.toString() + "api/v1/mesh-test-service-quarkus/sleep?seconds=" + SLEEP_DURATION_SECONDS_STRING, false);
    }

    @Test
    public void testLoadEnvoyWithConcurrency1WithoutConnectionLimit() throws Exception {
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
                    URL url = new URL(requestUrl);
                    HttpURLConnection httpURLConnection1 = (HttpURLConnection) url.openConnection();
                    httpURLConnection1.connect();
                    log.info("response_code:" + httpURLConnection1.getResponseCode());
                    responsesTimes.add(System.currentTimeMillis());
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
        List<Future<?>> futures = reqs.stream().map(executorService::submit).collect(Collectors.toList());
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

    private Request newRequest(int requestId, String gatewayUrl, String prefix) {
        return new Request.Builder().addHeader("x-request-id", String.valueOf(requestId)).url(gatewayUrl + prefix)
                .build();

    }

}