package com.netcracker.it.spring;

import com.netcracker.cloud.junit.cloudcore.extension.annotations.Cloud;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.PortForward;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.Value;
import com.netcracker.it.spring.model.svt.*;
import com.netcracker.it.spring.model.svt.url.IngressUrlProvider;
import com.netcracker.it.spring.model.svt.url.ServicePortForwardProvider;
import com.netcracker.it.spring.utils.ClosablePortForward;
import com.netcracker.it.spring.utils.KeyValuePair;
import com.netcracker.it.spring.watch.IngressWatcher;
import com.netcracker.it.spring.watch.ServiceWatcher;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceBuilder;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watcher;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;

import java.net.URL;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

import static com.netcracker.it.spring.Const.*;
import static com.netcracker.it.spring.utils.Utils.newMap;
import static com.netcracker.it.spring.utils.Utils.runWithRetry;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Slf4j
@Tag("bg-e2e-phase:svt[baseline]")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Disabled
public class RobustnessIT extends TestWithPortForward {

    public static final String SVT_LABEL = "netcracker.cloud/bg-svt-resource";
    public static final String SVT_TC3_SERVICE = "netcracker.cloud/bg-svt-tc3-service";

    private static final SvtThresholdsSet THRESHOLDS = SvtThresholdsSet.builder()
            .avgReplicationTime(TimeUnit.SECONDS.toMillis(80))
            .maxReplicationTime(TimeUnit.SECONDS.toMillis(110))
            .avgRoutesPreparationTime(TimeUnit.SECONDS.toMillis(100))
            .maxRoutesPreparationTime(TimeUnit.SECONDS.toMillis(130))
            .build();

    @Cloud(namespace = @Value(prop = ORIGIN_NAMESPACE_ENV_NAME))
    private static KubernetesClient platformClient;

    @PortForward(serviceName = @Value(PUBLIC_GW_SERVICE_NAME), cloud = @Cloud(namespace = @Value(prop = ORIGIN_NAMESPACE_ENV_NAME)))
    private static URL publicGWServerUrl;

    private ExecutorService executorService;

    @PortForward(serviceName = @Value(PRIVATE_GW_SERVICE_NAME), cloud = @Cloud(namespace = @Value(prop = PEER_NAMESPACE_ENV_NAME)))
    private static URL privateGWServerUrlPeer;
    @Cloud(namespace = @Value(prop = PEER_NAMESPACE_ENV_NAME))
    private static KubernetesClient platformClientPeer;

    @BeforeAll
    public static void initTests() throws Exception {
        assertNotNull(publicGWServerUrl);
        assertNotNull(platformClient);
        cleanupSvtResourcesAndWait();
    }

    @AfterAll
    public static void finalizeTests() throws Exception {
        cleanupSvtResourcesAndWait();
    }

    private static void cleanupSvtResourcesAndWait() throws Exception {
        log.info("Cleaning up test resources");
        cleanup();

        runWithRetry(() -> {
            final List<Ingress> ingresses = platformClient.network().v1().ingresses()
                    .inNamespace(CONTROLLER_NAMESPACE)
                    .withLabel(SVT_LABEL, "true")
                    .list().getItems();
            if (ingresses != null && !ingresses.isEmpty()) {
                throw new RuntimeException("Controller still contains SVT ingresses");
            }
            final List<Service> services = platformClient.services()
                    .inNamespace(CONTROLLER_NAMESPACE)
                    .withLabel(SVT_LABEL, "true")
                    .list().getItems();
            if (services != null && !services.isEmpty()) {
                throw new RuntimeException("Controller still contains SVT ingresses");
            }
        }, TimeUnit.MINUTES.toMillis(5));
        log.info("SVT resources cleanup finished");
    }

    @BeforeEach
    public void setUpExecutor() {
        executorService = Executors.newFixedThreadPool(64);
    }

    @AfterEach
    public void shutdown() {
        if (executorService != null) executorService.shutdown();
    }

    private Map<String, ReplicationTestCase> prepareServiceReplicationTestCases(final String serviceNamePrefix, final String targetBackend, final int startIdx, final int servicesNum) {
        final SvtTestCaseProvider<ReplicationTestCase> serviceTCProvider = (resourceName, backend) ->
                ReplicationTestCase.builder()
                        .platformClient(platformClient)
                        .urlProvider(new ServicePortForwardProvider(portForwardService))
                        .resource(buildTestService(PEER_NAMESPACE, resourceName, backend))
                        .requestTestCases(List.of(
                                new RequestTestCase(resourceName + ":8080", true, 200, backend),
                                new RequestTestCase(resourceName + ":8080", false, 404)))
                        .build();
        return prepareTestCases(serviceNamePrefix, "", targetBackend, startIdx, servicesNum, serviceTCProvider);
    }

    private Map<String, ReplicationTestCase> prepareIngressReplicationTestCases(final String serviceNamePrefix, final String targetBackend, final int startIdx, final int servicesNum) {
        final SvtTestCaseProvider<ReplicationTestCase> tcProvider = (resourceName, backend) -> {
            final String host = String.format("%s-%s.%s", resourceName, ORIGIN_NAMESPACE, ENV_CLOUD_PUBLIC_HOST);
            return ReplicationTestCase.builder()
                    .platformClient(platformClient)
                    .urlProvider(new IngressUrlProvider())
                    .resource(buildTestIngress(PEER_NAMESPACE, resourceName, host, resourceName))
                    .requestTestCases(List.of(
                            new RequestTestCase(true, 200, backend),
                            new RequestTestCase(false, 404)))
                    .build();
        };
        return prepareTestCases(serviceNamePrefix, "-"+PEER_NAMESPACE, targetBackend, startIdx, servicesNum, tcProvider);
    }

    private static <T extends SvtTestCase> Map<String, T> prepareTestCases(final String namePrefix, final String ingressNameSuffix, final String targetBackend, final int startIdx, final int resourcesNum, SvtTestCaseProvider<T> testCaseProvider) {
        final Map<String, T> testCases = new HashMap<>(resourcesNum);
        final int lastIdx = resourcesNum + startIdx - 1;
        for (int serviceNumber = startIdx; serviceNumber <= lastIdx; serviceNumber++) {
            final String resourceName = namePrefix + serviceNumber;
            testCases.put(resourceName + ingressNameSuffix, testCaseProvider.provideTestCase(resourceName, targetBackend));
        }
        return testCases;
    }

    private static void cleanup() {
        platformClient.services()
                .inNamespace(PEER_NAMESPACE)
                .withLabel(SVT_LABEL, "true")
                .delete();
        platformClient.network().v1().ingresses()
                .inNamespace(PEER_NAMESPACE)
                .withLabel(SVT_LABEL, "true")
                .delete();
    }

    @Test
    @Order(1)
    public void tc1TestServices() throws ExecutionException, InterruptedException, TimeoutException {
        log.info("Preparing test cases for TC1");
        final Map<String, ReplicationTestCase> testCasesMap = prepareServiceReplicationTestCases("bg-svt-1-", SPRING_SERVICE_NAME+"-v1", 1, 100);
        testCasesMap.putAll(prepareServiceReplicationTestCases("bg-svt-2-", GO_SERVICE_NAME+"-v1", 1, 100));

        log.info("Building SVT Test Suit");
        SvtTestSuit.builder()
                .platformClient(platformClient)
                .executorService(executorService)
                .thresholds(THRESHOLDS)
                .watchServiceAction(Watcher.Action.ADDED)
                .serviceTestCases(testCasesMap)
                .build()
                .run();
    }

    @Test
    @Order(2)
    public void tc2TestIngresses() throws ExecutionException, InterruptedException, TimeoutException {
        log.info("Preparing test cases for TC2");
        final Map<String, ReplicationTestCase> testCasesMap = prepareIngressReplicationTestCases("bg-svt-1-", SPRING_SERVICE_NAME+"-v1", 1, 25);
        testCasesMap.putAll(prepareIngressReplicationTestCases("bg-svt-2-", GO_SERVICE_NAME+"-v1", 1, 25));

        log.info("Building SVT Test Suit");
        SvtTestSuit.builder()
                .platformClient(platformClient)
                .executorService(executorService)
                .thresholds(THRESHOLDS)
                .watchIngressAction(Watcher.Action.ADDED)
                .ingressTestCases(testCasesMap)
                .build()
                .run();
    }

    @Test
    @Order(3)
    public void tc3TestIngressesWithoutServices() throws ExecutionException, InterruptedException, TimeoutException {
        log.info("Preparing test cases for TC3");
        // create 50 ingresses without services and test that they are replicated but edge-router returns 404 on requests
        SvtTestCaseProvider<ReplicationTestCase> ingressTCProvider = (resourceName, backend) -> {
            final String host = String.format("%s-%s.%s", resourceName, ORIGIN_NAMESPACE, ENV_CLOUD_PUBLIC_HOST);
            return ReplicationTestCase.builder()
                    .platformClient(platformClient)
                    .urlProvider(new IngressUrlProvider())
                    .resource(buildTestIngress(PEER_NAMESPACE, resourceName, host, resourceName))
                    .requestTestCases(List.of(
                            new RequestTestCase(true, 404),
                            new RequestTestCase(false, 404)))
                    .build();
        };
        Map<String, ReplicationTestCase> testCasesMap = prepareTestCases("bg-svt-1-", "-"+PEER_NAMESPACE, null, 101, 25, ingressTCProvider);
        testCasesMap.putAll(prepareTestCases("bg-svt-2-", "-"+PEER_NAMESPACE, null, 101, 25, ingressTCProvider));

        log.info("Running services SVT");
        SvtTestSuit.builder()
                .platformClient(platformClient)
                .executorService(executorService)
                .thresholds(THRESHOLDS)
                .watchIngressAction(Watcher.Action.ADDED)
                .ingressTestCases(testCasesMap)
                .build()
                .run();

        log.info("Creating services for the ingresses from previous step and test routing");
        ingressTCProvider = (resourceName, backend) -> ReplicationTestCase.builder()
                .platformClient(platformClient)
                .urlProvider(new IngressUrlProvider())
                .resource(buildTestService(PEER_NAMESPACE, resourceName, backend, true))
                .requestTestCases(List.of(
                        new RequestTestCase(true, 200, backend),
                        new RequestTestCase(false, 404)))
                .build();
        testCasesMap = prepareTestCases("bg-svt-1-", "", SPRING_SERVICE_NAME+"-v1", 101, 25, ingressTCProvider);
        testCasesMap.putAll(prepareTestCases("bg-svt-2-", "", GO_SERVICE_NAME+"-v1", 101, 25, ingressTCProvider));

        SvtTestSuit.builder()
                .platformClient(platformClient)
                .executorService(executorService)
                .thresholds(THRESHOLDS)
                .watchServiceAction(Watcher.Action.ADDED)
                .serviceTestCases(testCasesMap)
                .build()
                .run();
    }

    @Test
    @Order(4)
    public void tc4TestDeletion() throws Exception {
        log.info("Preparing test cases for TC4");
        // delete 50 services and verify that edge-router returns 404 on requests
        final SvtTestCaseProvider<DeletionTestCase> tcProvider = (resourceName, backend) -> DeletionTestCase.builder()
                .platformClient(platformClient)
                .urlProvider(new IngressUrlProvider())
                .resource(buildDummyService(PEER_NAMESPACE, resourceName))
                .requestTestCases(List.of(
                        new RequestTestCase(true, 404),
                        new RequestTestCase(false, 404)))
                .build();
        final Map<String, DeletionTestCase> testCasesMap = prepareTestCases("bg-svt-1-", "", null, 101, 25, tcProvider);
        testCasesMap.putAll(prepareTestCases("bg-svt-2-", "", null, 101, 25, tcProvider));

        log.info("Removing services created in TC3");
        SvtTestSuit.builder()
                .platformClient(platformClient)
                .executorService(executorService)
                .thresholds(THRESHOLDS)
                .watchServiceAction(Watcher.Action.DELETED)
                .serviceTestCases(testCasesMap)
                .build()
                .run();

        // delete all the remaining entities and verify that envoy has no bg-svt routes in config dump
        log.info("Removing all the remaining SVT resources");
        var ref = new Object() {
            long startTime = 0L;
        };
        final List<Long> replicationTime = Collections.synchronizedList(new LinkedList<>());
        final CountDownLatch countDownLatch = new CountDownLatch(300); // 200 services TC1 + 50 ingresses TC2 + 50 ingresses TC3
        try (IngressWatcher ingressWatcher = new IngressWatcher(platformClient, CONTROLLER_NAMESPACE, ((action, ingress) -> {
            if (action.equals(Watcher.Action.DELETED)) {
                replicationTime.add(System.currentTimeMillis() - ref.startTime);
                log.info("[Ingress] Deleted resources num: {}", replicationTime.size());
                countDownLatch.countDown();
            }
        }))) {
            try (ServiceWatcher serviceWatcher = new ServiceWatcher(platformClient, CONTROLLER_NAMESPACE, ((action, ingress) -> {
                if (action.equals(Watcher.Action.DELETED)) {
                    replicationTime.add(System.currentTimeMillis() - ref.startTime);
                    log.info("[Service] Deleted resources num: {}", replicationTime.size());
                    countDownLatch.countDown();
                }
            }))) {
                ref.startTime = System.currentTimeMillis();
                deleteAllTestResources();
                assertTrue(countDownLatch.await(10, TimeUnit.MINUTES));
            }
        }

        long avgReplicationTime = 0L;
        for (int i = 0; i < replicationTime.size(); i++) {
            avgReplicationTime += (replicationTime.get(i) - avgReplicationTime) / (i + 1);
        }
        log.info("Average resource deletion time: {}", avgReplicationTime);

        assertTrue(verifyEnvoyDoesntContainSvtRoutes());
    }

    private boolean verifyEnvoyDoesntContainSvtRoutes() throws Exception {
        final Pattern regexPattern = Pattern.compile("bg-svt-\\d-\\d");
        final long deadline = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(10);
        try (ClosablePortForward portForward = createPortForward(CONTROLLER_NAMESPACE, EDGE_ROUTER_SERVICE_NAME, 9901)) {
            for (;;) {
                final String configDump = CommonOperations.getEnvoyConfigAsString(portForward.getUrl());
                if (regexPattern.matcher(configDump).find()) {
                    Thread.sleep(200);
                    if (System.currentTimeMillis() < deadline) {
                        continue;
                    }
                    return false;
                } else {
                    return true;
                }
            }
        }
    }

    private void deleteAllTestResources() {
        platformClient.services().inNamespace(PEER_NAMESPACE).withLabel(SVT_LABEL, "true").delete();
        platformClient.network().v1().ingresses().inNamespace(PEER_NAMESPACE).withLabel(SVT_LABEL, "true").delete();
    }

    private GenericKubernetesResource buildTestService(final String namespace, final String name, final String targetBackendName) {
        return buildTestService(namespace, name, targetBackendName, false);
    }

    private GenericKubernetesResource buildTestService(final String namespace, final String name, final String targetBackendName, final boolean isTC3Service) {
        return new GenericKubernetesResourceBuilder()
                .withKind("Service")
                .withApiVersion("v1")
                .withMetadata(new ObjectMetaBuilder()
                        .withName(name)
                        .withNamespace(namespace)
                        .withLabels(isTC3Service
                                ? newMap(new KeyValuePair<>(SVT_LABEL, "true"), new KeyValuePair<>(SVT_TC3_SERVICE, "true"))
                                : newMap(new KeyValuePair<>(SVT_LABEL, "true")))
                        .build())
                .addToAdditionalProperties("spec", newMap(
                        new KeyValuePair<>("selector", Collections.singletonMap("name", targetBackendName)),
                        new KeyValuePair<>("ports", Collections.singletonList(newMap(
                                new KeyValuePair<>("name", "web"),
                                new KeyValuePair<>("port", 8080),
                                new KeyValuePair<>("targetPort", 8080))))
                ))
                .build();
    }

    // dummy ingress to be used in DeletionTestCase instances: only apiVersion, kind and metadata.name fields are needed.
    private GenericKubernetesResource buildDummyService(final String namespace, final String ingressName) {
        return new GenericKubernetesResourceBuilder()
                .withKind("Service")
                .withApiVersion("v1")
                .withMetadata(new ObjectMetaBuilder().withName(ingressName).withNamespace(namespace).build())
                .build();
    }

    private GenericKubernetesResource buildTestIngress(final String namespace, final String ingressName, final String host, final String serviceName) {
        return new GenericKubernetesResourceBuilder()
                .withKind("Ingress")
                .withApiVersion("networking.k8s.io/v1")
                .withMetadata(new ObjectMetaBuilder()
                        .withName(ingressName)
                        .withNamespace(namespace)
                        .withLabels(Collections.singletonMap(SVT_LABEL, "true"))
                        .addToAnnotations("nginx.ingress.kubernetes.io/ssl-redirect", "true").build())
                .addToAdditionalProperties("spec",
                        newMap(new KeyValuePair<>("rules", Collections.singletonList(
                                newMap(
                                        new KeyValuePair<>("host", host),
                                        new KeyValuePair<>("http", Collections.singletonMap("paths", Collections.singletonList(
                                                newMap(
                                                        new KeyValuePair<>("pathType", "Prefix"),
                                                        new KeyValuePair<>("path", "/"),
                                                        new KeyValuePair<>("backend", Collections.singletonMap("service",
                                                                newMap(
                                                                        new KeyValuePair<>("name", serviceName),
                                                                        new KeyValuePair<>("port", Collections.singletonMap("name", "web"))
                                                                )
                                                        ))
                                                )
                                        )
                        )
                ))))))
                .build();
    }

    @FunctionalInterface
    private interface SvtTestCaseProvider<T extends SvtTestCase> {
        T provideTestCase(final String resourceName, final String targetBackend);
    }
}
