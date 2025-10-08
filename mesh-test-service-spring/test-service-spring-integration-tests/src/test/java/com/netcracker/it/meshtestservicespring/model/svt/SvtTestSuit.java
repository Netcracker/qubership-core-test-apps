package com.netcracker.it.meshtestservicespring.model.svt;

import io.fabric8.kubernetes.client.KubernetesClient;
import com.netcracker.it.meshtestservicespring.watch.IngressWatcher;
import com.netcracker.it.meshtestservicespring.watch.ServiceWatcher;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
import io.fabric8.kubernetes.client.Watcher;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.BiConsumer;

import static com.netcracker.it.meshtestservicespring.Const.CONTROLLER_NAMESPACE;
import static com.netcracker.it.meshtestservicespring.Const.PEER_NAMESPACE;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Slf4j
@Builder
public class SvtTestSuit {
    private KubernetesClient platformClient;
    private ExecutorService executorService;
    private Map<String, ? extends SvtTestCase> ingressTestCases;
    private Map<String, ? extends SvtTestCase> serviceTestCases;
    private Watcher.Action watchServiceAction;
    private Watcher.Action watchIngressAction;
    private SvtThresholdsSet thresholds;

    public void run() throws ExecutionException, InterruptedException, TimeoutException {
        log.info("Running test suit");
        final int totalTestCases = (ingressTestCases == null ? 0 : ingressTestCases.size())
                + (serviceTestCases == null ? 0 : serviceTestCases.size());
        final CountDownLatch countDownLatch = new CountDownLatch(totalTestCases);
        ServiceWatcher serviceWatcher = null;
        IngressWatcher ingressWatcher = null;

        try {
            // start watchers
            if (watchServiceAction != null) {
                serviceWatcher = new ServiceWatcher(platformClient, CONTROLLER_NAMESPACE, buildServiceEventConsumerOnAction(watchServiceAction, countDownLatch));
            }
            if (watchIngressAction != null) {
                ingressWatcher = new IngressWatcher(platformClient, CONTROLLER_NAMESPACE, buildIngressEventConsumerOnAction(watchIngressAction, countDownLatch));
            }
            log.info("Watchers started");

            // replicate all resources in parallel as concurrent as possible
            if (serviceTestCases != null) replicateResources(serviceTestCases);
            if (ingressTestCases != null) replicateResources(ingressTestCases);

            // wait until all the resources are replicated
            assertTrue(countDownLatch.await(10, TimeUnit.MINUTES));
        } finally {
            if (serviceWatcher != null) serviceWatcher.close();
            if (ingressWatcher != null) ingressWatcher.close();
        }

        verifyAndLogResults();
    }

    private void replicateResources(final Map<String, ? extends SvtTestCase> testCasesMap) throws ExecutionException, InterruptedException, TimeoutException {
        log.info("Replicating resources");
        executorService.submit(() -> testCasesMap.values().parallelStream()
                .forEach(testCase -> testCase.applyResource(PEER_NAMESPACE)))
                .get(10, TimeUnit.MINUTES);
    }

    private BiConsumer<Watcher.Action, Service> buildServiceEventConsumerOnAction(final Watcher.Action desiredAction, final CountDownLatch countDownLatch) {
        return (action, resource) -> {
            if (action == desiredAction) {
                final SvtTestCase testCase = serviceTestCases.get(resource.getMetadata().getName());
                if (testCase == null) {
                    return;
                }
                testCase.setReplicationEndTime(System.currentTimeMillis());
                testCase.setReplicationSucceeded(true);
                executorService.submit(() -> testReplicatedResource(testCase, countDownLatch));
            }
        };
    }

    private BiConsumer<Watcher.Action, Ingress> buildIngressEventConsumerOnAction(final Watcher.Action desiredAction, final CountDownLatch countDownLatch) {
        return (action, resource) -> {
            if (action == desiredAction) {
                final SvtTestCase testCase = ingressTestCases.get(resource.getMetadata().getName());
                if (testCase == null) {
                    return;
                }
                testCase.setReplicationEndTime(System.currentTimeMillis());
                testCase.setReplicationSucceeded(true);
                executorService.submit(() -> testReplicatedResource(testCase, countDownLatch));
            }
        };
    }

    private void testReplicatedResource(final SvtTestCase testCase, final CountDownLatch countDownLatch) {
        testCase.verifyRequests();
        countDownLatch.countDown();
    }

    private void verifyAndLogResults() {
        final List<SvtTestCase> testCases = new ArrayList<>();
        if (ingressTestCases != null) {
            testCases.addAll(ingressTestCases.values());
        }
        if (serviceTestCases != null) {
            testCases.addAll(serviceTestCases.values());
        }
        double avgReplicationTime = 0;
        double avgRoutesPreparationTime = 0;
        long minReplicationTime = testCases.get(0).getReplicationTime();
        long maxReplicationTime = minReplicationTime;
        long minRoutesPreparationTime = testCases.get(0).getRequestTestCases().get(0).getTookMillis();;
        long maxRoutesPreparationTime = minRoutesPreparationTime;
        for (int i = 0; i < testCases.size(); i++) {
            SvtTestCase testCase = testCases.get(i);
            final String resource = testCase.getResource().getKind() + " " + testCase.getResource().getMetadata().getName();
            final long replicationTime = testCase.getReplicationTime();
            final long routesPreparationTime = testCase.getRequestTestCases().get(0).getTookMillis();
            log.debug("Resource {} replication succeeded:{}, took {} milliseconds, routes became ready in {} milliseconds", resource, testCase.isReplicationSucceeded(), replicationTime, routesPreparationTime);
            assertTrue(testCase.isPassed());

            avgReplicationTime += (replicationTime - avgReplicationTime) / (i + 1);
            avgRoutesPreparationTime += (routesPreparationTime - avgRoutesPreparationTime) / (i + 1);

            if (replicationTime > maxReplicationTime) maxReplicationTime = replicationTime;
            else if (replicationTime < minReplicationTime) minReplicationTime = replicationTime;

            if (routesPreparationTime > maxRoutesPreparationTime) maxRoutesPreparationTime = routesPreparationTime;
            else if (routesPreparationTime < minRoutesPreparationTime) minRoutesPreparationTime = routesPreparationTime;
        }
        log.info("{} avg replication time: {} milliseconds; max replication time: {} milliseconds; min replication time: {} milliseconds", testCases.get(0).getResource().getKind(), avgReplicationTime, maxReplicationTime, minReplicationTime);
        log.info("{} avg routes preparation time: {} milliseconds; max routes preparation time: {} milliseconds; min routes preparation time: {} milliseconds", testCases.get(0).getResource().getKind(), avgRoutesPreparationTime, maxRoutesPreparationTime, minRoutesPreparationTime);

        assertTrue(thresholds.getAvgReplicationTime() >= avgReplicationTime);
        assertTrue(thresholds.getMaxReplicationTime() >= maxReplicationTime);
        assertTrue(thresholds.getAvgRoutesPreparationTime() >= avgRoutesPreparationTime);
        assertTrue(thresholds.getMaxRoutesPreparationTime() >= maxRoutesPreparationTime);
    }
}
