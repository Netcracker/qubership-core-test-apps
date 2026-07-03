package com.netcracker.it.spring;

import com.google.gson.Gson;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.Cloud;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.EnableExtension;
import com.netcracker.it.spring.model.TraceResponse;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static com.netcracker.it.spring.Const.INTERNAL_GW_SERVICE_NAME;
import static com.netcracker.it.spring.Const.PUBLIC_GW_SERVICE_NAME;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mesh-agnostic test for stickiness ({@code StatefulSession}) and load-balancing ({@code LoadBalance}) CRs. 
 * The same behaviour must hold for both meshes; which CRs are applied
 * is decided by the {@code SERVICE_MESH_TYPE} guards in the mesh-test-service-spring chart
 * (Core: {@code Mesh subKind: StatefulSession/LoadBalance}; Istio: {@code DestinationRule.consistentHash}).
 * <p>
 * Requests go through the public and internal gateways via in-cluster {@code curl} (run inside the
 * mesh-test-service-spring pod). curl's own cookie jar captures and replays the sticky cookie. 
 * With {@code REPLICAS: 2} pod pinning is observable via {@code podId}.
 * <p>
 */
@Slf4j
@EnableExtension
@Tag("LoadBalance")
public class LoadBalanceIT {

    private static final String LB_TEST_PATH   = "/api/v1/mesh-test-service-spring/lb-test";
    private static final String LB_HEADER_PATH = "/api/v1/mesh-test-service-spring/lb-header";

    // Core suffixes the cookie with the b/g version (sticky-cookie-spring-v1); Istio keeps it verbatim.
    private static final String STICKY_COOKIE_BASE_NAME = "sticky-cookie-spring";
    private static final String LB_HEADER_NAME          = "X-User-Id";

    private static final int STICKY_REQUESTS = 10;
    private static final Gson GSON = new Gson();

    @Cloud
    protected static KubernetesClient kubernetesClient;

    @BeforeAll
    public static void setUp() {
        IntegrationTestSupport.init(kubernetesClient);
        Assumptions.assumeTrue(IntegrationTestSupport.isExecutorAvailable(),
                "mesh-test-service-spring pod not found — load-balance IT requires in-cluster curl executor");
    }

    static Stream<Arguments> gateways() {
        return Stream.of(
                Arguments.of(PUBLIC_GW_SERVICE_NAME),
                Arguments.of(INTERNAL_GW_SERVICE_NAME)
        );
    }

    // Cookie stickiness: the generated session cookie must pin every request to the same pod.
    @ParameterizedTest(name = "cookie stickiness via {0}")
    @MethodSource("gateways")
    void cookieStickiness_pinsAllRequestsToSamePod(String gateway) throws Exception {
        String url = IntegrationTestSupport.inClusterServiceUrl(gateway, LB_TEST_PATH);
        String cookieJar = "/tmp/lb-" + gateway + "-" + UUID.randomUUID() + ".cookies";

        TraceResponse first = awaitTrace(url, "-c", cookieJar);
        assertTrue(cookieJarContains(cookieJar, STICKY_COOKIE_BASE_NAME),
                gateway + ": mesh must issue the " + STICKY_COOKIE_BASE_NAME + " cookie");
        log.info("[{}] sticky cookie issued by pod {}", gateway, first.getPodId());

        Set<String> pods = new LinkedHashSet<>();
        pods.add(first.getPodId());
        for (int i = 0; i < STICKY_REQUESTS; i++) {
            pods.add(awaitTrace(url, "-b", cookieJar).getPodId());
        }
        log.info("[{}] pods that served cookie-pinned requests: {}", gateway, pods);
        assertEquals(1, pods.size(),
                gateway + ": cookie stickiness must pin every request to a single pod, but saw: " + pods);
        assertTrue(pods.contains(first.getPodId()),
                gateway + ": sticky pod must match the pod that issued the cookie");
    }

    // Header hash: requests with the same X-User-Id must hit the same pod (dedicated host avoids Istio DR collision).
    @ParameterizedTest(name = "header hash via {0}")
    @MethodSource("gateways")
    void headerHash_pinsRequestsWithSameHeaderValueToSamePod(String gateway) {
        String url = IntegrationTestSupport.inClusterServiceUrl(gateway, LB_HEADER_PATH);
        String header = LB_HEADER_NAME + ": " + UUID.randomUUID();

        Set<String> pods = new LinkedHashSet<>();
        for (int i = 0; i < STICKY_REQUESTS; i++) {
            pods.add(awaitTrace(url, "-H", header).getPodId());
        }
        log.info("[{}] pods for '{}': {}", gateway, header, pods);
        assertEquals(1, pods.size(),
                gateway + ": header consistent-hash must pin one header value to a single pod, but saw: " + pods);
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────────

    /** curls {@code url} in-cluster, retrying (via awaitility) until it returns a trace with a podId. */
    private static TraceResponse awaitTrace(String url, String... curlArgs) {
        AtomicReference<TraceResponse> ref = new AtomicReference<>();
        await().atMost(Duration.ofMinutes(2))
                .pollInterval(Duration.ofMillis(IntegrationTestSupport.POLL_INTERVAL_MS))
                .ignoreExceptions()
                .until(() -> {
                    List<String> cmd = new ArrayList<>(List.of("curl", "-s"));
                    cmd.addAll(Arrays.asList(curlArgs));
                    cmd.add(url);
                    String body = IntegrationTestSupport.executeInClusterPod(cmd.toArray(new String[0]));
                    TraceResponse trace = body == null || body.isBlank()
                            ? null : GSON.fromJson(body.trim(), TraceResponse.class);
                    ref.set(trace);
                    return trace != null && trace.getPodId() != null;
                });
        TraceResponse trace = ref.get();
        assertNotNull(trace, "trace response from " + url);
        return trace;
    }

    private static boolean cookieJarContains(String cookieJar, String cookieName) throws Exception {
        String jar = IntegrationTestSupport.executeInClusterPod("cat", cookieJar);
        return jar != null && jar.contains(cookieName);
    }
}
