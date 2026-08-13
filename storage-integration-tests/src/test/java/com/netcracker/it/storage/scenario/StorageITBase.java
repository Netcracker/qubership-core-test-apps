package com.netcracker.it.storage.scenario;

import com.netcracker.cloud.junit.cloudcore.extension.annotations.Cloud;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.IntValue;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.PortForward;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.Value;
import com.netcracker.it.storage.app.StorageTestApp;
import com.netcracker.it.storage.app.WorkloadStats;
import com.netcracker.it.storage.controller.FaultController;
import com.netcracker.it.storage.controller.PatroniFaultController;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URL;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static com.netcracker.it.storage.scenario.StorageAssertions.assertContract;
import static com.netcracker.it.storage.scenario.StorageAssertions.assertNoLeak;
import static com.netcracker.it.storage.scenario.StorageAssertions.assertNothingHung;
import static org.awaitility.Awaitility.await;

/**
 * The scenarios themselves, written once. A storage supplies its probe name and thresholds; the
 * faults, the workload shape and the assertions are shared.
 */
public abstract class StorageITBase {

    private static final Logger log = LoggerFactory.getLogger(StorageITBase.class);

    private static final Duration STABILISATION = Duration.ofMinutes(3);
    /** Long enough for the timeline to show a clear before, during and after. */
    private static final Duration WARM_UP = Duration.ofSeconds(15);
    private static final Duration SETTLE = Duration.ofSeconds(45);
    private static final long MIN_OPERATIONS = 30;

    private static final String NAMESPACE = System.getProperty("storage.namespace");
    private static final String LABEL_KEY = System.getProperty("storage.labelKey");
    private static final String LABEL_VALUE = System.getProperty("storage.labelValue");

    /** Namespace of the application, as the integration-test runner already passes it. */
    private static String appNamespace() {
        return Stream.of("ORIGIN_NAMESPACE", "env.cloud-namespace", "clouds.cloud.namespaces.namespace")
                .map(System::getProperty)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "the application namespace is not set; run through run-it/run-integration-tests.sh"));
    }

    @PortForward(serviceName = @Value("storage-test-service-spring"), port = @IntValue(8080))
    protected static URL appUrl;

    /**
     * Injected and closed by the extension. The namespace is the storage's, not the application's,
     * because this client drives the storage members.
     */
    @Cloud(namespace = @Value(prop = "storage.namespace"))
    protected static KubernetesClient kubernetes;

    protected StorageTestApp app;
    protected FaultController faults;

    private long faultClearedAt;

    /** Probe name in the application contract, for example {@code postgresql}. */
    protected abstract String storage();

    protected abstract Thresholds thresholds();

    /** Operations per second the workload issues; slower for storages whose calls are expensive. */
    protected int operationsPerSecond() {
        return 10;
    }

    /**
     * The fault is a Patroni leader change for every storage covered so far: directly for the DBaaS
     * client, and through maas-service's own database for the MaaS client.
     */
    protected FaultController newController() {
        return new PatroniFaultController(kubernetes, NAMESPACE, LABEL_KEY, LABEL_VALUE);
    }

    /**
     * Services this storage cannot be tested without. Checked up front so a missing install fails
     * with its own name rather than as a client timeout thirty operations later.
     */
    protected List<String> requiredServices() {
        return List.of();
    }

    @BeforeEach
    void setUpFixture() {
        requireServices();
        app = new StorageTestApp(appUrl);
        faults = newController();
        // start healthy, so a previous scenario's damage is never attributed to this one
        faults.awaitStable(STABILISATION);
        app.initStorage(storage());
    }

    private void requireServices() {
        String namespace = appNamespace();
        List<String> missing = requiredServices().stream()
                .filter(name -> kubernetes.services().inNamespace(namespace).withName(name).get() == null)
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException(storage() + " tests need " + missing
                    + " in namespace " + namespace + ", and they are not deployed."
                    + " Run the workflow with install-maas enabled, or exclude this suite.");
        }
    }

    @AfterEach
    void tearDownFixture() {
        try {
            app.stopWorkload();
        } finally {
            faults.awaitStable(STABILISATION);
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Fault.class)
    void clientRecoversFrom(Fault fault) {
        assertContract(runWorkloadThrough(fault, "PER_CALL"), faultClearedAt, thresholds(), MIN_OPERATIONS);
    }

    @Test
    @DisplayName("recovery works for a handle acquired once at startup")
    void longHeldHandleRecovers() {
        // the access pattern of a service that wires its handle at boot; the library's recovery
        // path is only entered when the caller asks again
        assertContract(runWorkloadThrough(Fault.ABRUPT_LEADER_LOSS, "LONG_HELD"),
                faultClearedAt, thresholds(), MIN_OPERATIONS);
    }

    @Test
    @DisplayName("repeated failover leaves no threads or descriptors behind")
    void resourceHygiene() {
        app.startWorkload(storage(), "PER_CALL", operationsPerSecond());
        letWorkloadRun(WARM_UP);
        Map<String, Object> baseline = app.diag();
        log.info("Leak baseline: {}", baseline);

        for (int cycle = 1; cycle <= thresholds().leakCycles(); cycle++) {
            log.info("Leak cycle {}/{}", cycle, thresholds().leakCycles());
            Fault.ABRUPT_LEADER_LOSS.injectAndAwaitRecovery(faults);
            letWorkloadRun(Duration.ofSeconds(10));
        }

        letWorkloadRun(SETTLE);
        Map<String, Object> after = app.diag();
        log.info("Leak after {} cycles: {}", thresholds().leakCycles(), after);

        assertNoLeak(baseline, after, thresholds());
        assertNothingHung(app.stats(), thresholds());
    }

    /** Warm up, inject, let it settle, return the timeline. The shape every scenario shares. */
    private WorkloadStats runWorkloadThrough(Fault fault, String handleMode) {
        app.startWorkload(storage(), handleMode, operationsPerSecond());
        letWorkloadRun(WARM_UP);
        faultClearedAt = fault.injectAndAwaitRecovery(faults);
        letWorkloadRun(SETTLE.plus(thresholds().recovery()));
        return app.stats();
    }

    private static void letWorkloadRun(Duration duration) {
        await().pollDelay(duration).atMost(duration.plusSeconds(5)).until(() -> true);
    }
}
