package com.netcracker.it.spring.envoy;

import com.google.gson.Gson;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.Cloud;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.EnableExtension;
import com.netcracker.it.common.model.TraceResponse;
import io.fabric8.kubernetes.api.model.apps.DeploymentStatus;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static com.netcracker.it.spring.CommonOperations.getCountOfReplicas;
import static com.netcracker.it.spring.CommonOperations.scaleDeployment;
import static com.netcracker.it.spring.envoy.Paths.API_V_1_MESH_TEST_SERVICE_SPRING;
import static com.netcracker.it.spring.envoy.Paths.INTERNAL_GATEWAY_BASE_PATH;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Cookie stickiness ({@code StatefulSession} / {@code DestinationRule} cookie hash) and header-hash load balancing
 * ({@code LoadBalance} / {@code DestinationRule} header hash) must behave the same on both meshes.
 * The test scales spring to two replicas for its own run, so that pinning is observable via {@code podId}.
 */
@EnableExtension
@Slf4j
@Tag("LoadBalance")
class EnvoyLoadBalanceIT extends BaseTest {

    private static final String PUBLIC_GATEWAY = "public-gateway";
    private static final String INTERNAL_GATEWAY = "internal-gateway";

    private static final String LB_TEST_PATH = API_V_1_MESH_TEST_SERVICE_SPRING + "lb-test";
    private static final String LB_HEADER_PATH = API_V_1_MESH_TEST_SERVICE_SPRING + "lb-header";

    // Core suffixes the cookie with the b/g version (sticky-cookie-spring-v1); Istio keeps it verbatim
    private static final String STICKY_COOKIE_BASE_NAME = "sticky-cookie-spring";
    private static final String LB_HEADER_NAME = "X-User-Id";
    private static final int STICKY_REQUESTS = 10;

    // mesh resources reach the gateways asynchronously after the chart is installed
    private static final Duration READY_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(2);

    // other suites keep one replica: Envoy lets each extra endpoint exceed a connection limit
    private static final String SPRING_DEPLOYMENT = "mesh-test-service-spring-v1";
    private static final int LB_REPLICAS = 2;
    private static final Duration SCALE_TIMEOUT = Duration.ofMinutes(3);
    // null until captured: scaling back to an unknown count would take spring down for the next suites
    private static Integer originalReplicas;

    @Cloud
    protected static KubernetesClient kubernetesClient;

    private static InClusterCurl inClusterCurl;

    @BeforeAll
    static void scaleUpSpringAndInitInClusterCurl() {
        originalReplicas = getCountOfReplicas(kubernetesClient, kubernetesClient.getNamespace(), SPRING_DEPLOYMENT);
        scaleSpring(LB_REPLICAS);
        inClusterCurl = new InClusterCurl(kubernetesClient);
    }

    @AfterAll
    static void scaleSpringBack() {
        if (originalReplicas != null) {
            scaleSpring(originalReplicas);
        }
    }

    private static void scaleSpring(int replicas) {
        String namespace = kubernetesClient.getNamespace();
        scaleDeployment(kubernetesClient, namespace, SPRING_DEPLOYMENT, replicas);
        // terminating pods still serve until they are gone, and the next suite must see the final count
        await("%s ready with %d pods".formatted(SPRING_DEPLOYMENT, replicas))
                .atMost(SCALE_TIMEOUT)
                .pollInterval(POLL_INTERVAL)
                .until(() -> {
                    DeploymentStatus status = kubernetesClient.apps().deployments().inNamespace(namespace)
                            .withName(SPRING_DEPLOYMENT).get().getStatus();
                    // evicted or completed pods never go away by themselves
                    long livePods = kubernetesClient.pods().inNamespace(namespace).withLabel("name", SPRING_DEPLOYMENT)
                            .list().getItems().stream()
                            .filter(pod -> !Set.of("Failed", "Succeeded").contains(pod.getStatus().getPhase()))
                            .count();
                    return status != null && Integer.valueOf(replicas).equals(status.getReadyReplicas())
                            && livePods == replicas;
                });
    }

    static Stream<String> gateways() {
        return Stream.of(PUBLIC_GATEWAY, INTERNAL_GATEWAY);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("gateways")
    void testCookieStickinessPinsRequestsToOnePod(String gateway) {
        // Istio gives a cookieless client the same generated cookie every time, a random one spreads the probes;
        // Core names its cookie after the version, so it ignores this one and balances freely
        awaitAllReplicasBehind(gateway, LB_TEST_PATH,
                () -> Map.of("Cookie", STICKY_COOKIE_BASE_NAME + "=" + UUID.randomUUID()));
        HttpResult first = awaitTrace(gateway, LB_TEST_PATH, Map.of());
        String stickyCookie = first.setCookies().stream()
                .filter(cookie -> cookie.startsWith(STICKY_COOKIE_BASE_NAME))
                .map(cookie -> cookie.split(";", 2)[0])
                .findFirst()
                .orElseThrow(() -> new AssertionError("%s: mesh must issue the %s cookie, got Set-Cookie %s"
                        .formatted(gateway, STICKY_COOKIE_BASE_NAME, first.setCookies())));
        String stickyPod = first.trace().getPodId();
        log.info("[{}] {} issued by pod {}", gateway, stickyCookie, stickyPod);

        Set<String> pods = new LinkedHashSet<>(List.of(stickyPod));
        for (int i = 0; i < STICKY_REQUESTS; i++) {
            pods.add(awaitTrace(gateway, LB_TEST_PATH, Map.of("Cookie", stickyCookie)).trace().getPodId());
        }
        assertEquals(Set.of(stickyPod), pods,
                "%s: cookie stickiness must pin every request to the pod that issued the cookie".formatted(gateway));
    }

    // Separate -lb Service: Istio allows one consistentHash per host, the cookie one is on the main Service
    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("gateways")
    void testHeaderHashPinsSameHeaderValueToOnePod(String gateway) {
        // without X-User-Id there is nothing to hash, so the probes are spread
        awaitAllReplicasBehind(gateway, LB_HEADER_PATH, Map::of);
        Map<String, String> headers = Map.of(LB_HEADER_NAME, UUID.randomUUID().toString());

        Set<String> pods = new LinkedHashSet<>();
        for (int i = 0; i < STICKY_REQUESTS; i++) {
            pods.add(awaitTrace(gateway, LB_HEADER_PATH, headers).trace().getPodId());
        }
        assertEquals(1, pods.size(),
                "%s: header consistent hash must pin one %s value to a single pod, but saw %s"
                        .formatted(gateway, LB_HEADER_NAME, pods));
    }

    // pinning to one pod proves nothing until the route reaches both replicas
    private void awaitAllReplicasBehind(String gateway, String path, Supplier<Map<String, String>> probeHeaders) {
        Set<String> pods = new HashSet<>();
        await("%s %s reaches %d pods".formatted(gateway, path, LB_REPLICAS))
                .atMost(READY_TIMEOUT)
                .pollInterval(Duration.ofMillis(500))
                .ignoreExceptions()
                .until(() -> {
                    HttpResult result = get(gateway, path, probeHeaders.get());
                    if (result.status() == 200) {
                        pods.add(result.trace().getPodId());
                    }
                    return pods.size() >= LB_REPLICAS;
                });
    }

    private HttpResult awaitTrace(String gateway, String path, Map<String, String> headers) {
        AtomicReference<HttpResult> result = new AtomicReference<>();
        await("%s %s".formatted(gateway, path))
                .atMost(READY_TIMEOUT)
                .pollInterval(POLL_INTERVAL)
                .ignoreExceptions()
                .untilAsserted(() -> {
                    HttpResult current = get(gateway, path, headers);
                    assertEquals(200, current.status(), "body: " + current.body());
                    assertNotNull(current.trace().getPodId(), "no podId in " + current.body());
                    result.set(current);
                });
        return result.get();
    }

    private HttpResult get(String gateway, String path, Map<String, String> headers) throws Exception {
        if (PUBLIC_GATEWAY.equals(gateway)) {
            try (Response response = executeGetRequest(publicGWServerUrl, path, headers)) {
                return new HttpResult(response.code(), response.headers("Set-Cookie"), response.body().string());
            }
        }
        return HttpResult.fromCurlOutput(inClusterCurl.get(INTERNAL_GATEWAY_BASE_PATH + path, headers));
    }

    private record HttpResult(int status, List<String> setCookies, String body) {

        private static final String SET_COOKIE = "set-cookie:";

        static HttpResult fromCurlOutput(String output) {
            String[] headersAndBody = output.split("\r\n\r\n", 2);
            String[] headerLines = headersAndBody[0].split("\r\n");
            int status = Integer.parseInt(headerLines[0].split(" ")[1]);
            List<String> setCookies = Arrays.stream(headerLines)
                    .skip(1)
                    .filter(line -> line.regionMatches(true, 0, SET_COOKIE, 0, SET_COOKIE.length()))
                    .map(line -> line.substring(SET_COOKIE.length()).trim())
                    .toList();
            return new HttpResult(status, setCookies, headersAndBody.length > 1 ? headersAndBody[1] : "");
        }

        TraceResponse trace() {
            return new Gson().fromJson(body, TraceResponse.class);
        }
    }
}
