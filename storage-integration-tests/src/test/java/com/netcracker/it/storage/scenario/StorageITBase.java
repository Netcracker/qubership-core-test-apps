package com.netcracker.it.storage.scenario;

import com.netcracker.cloud.junit.cloudcore.extension.annotations.Cloud;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.IntValue;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.PortForward;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.Value;
import com.netcracker.it.storage.app.MaasAgent;
import com.netcracker.it.storage.app.StorageTestApp;
import com.netcracker.it.storage.app.WorkloadStats;
import com.netcracker.it.storage.controller.FaultController;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.awaitility.core.ConditionTimeoutException;
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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The scenarios themselves, written once. A subclass supplies the platform it drives and the
 * storage profile it exercises; the faults, the workload shape and the assertions are shared.
 *
 * <p>The per-class lifecycle is what lets the fault list come from the profile: a
 * {@code @MethodSource} factory may only be an instance method under it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class StorageITBase {

    private static final Logger log = LoggerFactory.getLogger(StorageITBase.class);

    private static final Duration STABILISATION = Duration.ofMinutes(3);
    /** Long enough for the timeline to show a clear before, during and after. */
    private static final Duration WARM_UP = Duration.ofSeconds(15);
    /** Clean traffic observed after recovery, which is what "the errors stopped" is asserted on. */
    private static final Duration QUIET_PERIOD = Duration.ofSeconds(30);
    /** Slack over the recovery allowance, so a late success is reported by the assertion. */
    private static final Duration RECOVERY_GRACE = Duration.ofSeconds(15);
    private static final long MIN_OPERATIONS = 30;

    /**
     * Injected and closed by the extension. The namespace is the storage's, not the application's,
     * because this client drives the storage members.
     */
    @Cloud(namespace = @Value(prop = "storage.namespace"))
    protected static KubernetesClient kubernetes;

    /** Reached for topic reconciliation, which is a platform operation rather than a client call. */
    @PortForward(serviceName = @Value("maas-agent"), port = @IntValue(8080))
    protected static URL maasAgentUrl;

    protected StorageTestApp app;
    protected FaultController faults;

    protected long faultClearedAt;

    /** The application under test, port-forwarded by the platform's base class. */
    protected abstract URL appUrl();

    /** The storage this class exercises. */
    protected abstract StorageProfile profile();

    @BeforeEach
    void setUpFixture() {
        requireServices();
        app = new StorageTestApp(appUrl());
        faults = profile().newController(kubernetes);
        // start healthy, so a previous scenario's damage is never attributed to this one
        faults.awaitStable(STABILISATION);
        profile().awaitDependencies(kubernetes, STABILISATION);
        recoverTopics();
        app.initStorage(profile().probe());
    }

    /**
     * The local-dev broker has no volume, so losing its pod loses every topic while MaaS keeps the
     * registration. Reconciling here is what an operator would do after such an outage, and
     * without it the next scenario fails on setup with MAAS-0600 rather than on anything it means
     * to measure.
     */
    private void recoverTopics() {
        new MaasAgent(maasAgentUrl).recoverTopics(Namespaces.application());
    }

    private void requireServices() {
        String namespace = Namespaces.application();
        List<String> missing = profile().requiredServices().stream()
                .filter(name -> kubernetes.services().inNamespace(namespace).withName(name).get() == null)
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException(profile().probe() + " tests need " + missing
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

    /** The faults the storage under test can be subjected to. */
    Stream<Fault> faults() {
        return profile().faults().stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("faults")
    void clientRecoversFrom(Fault fault) {
        assertContract(runWorkloadThrough(fault, "PER_CALL"), faultClearedAt,
                profile().thresholds(), MIN_OPERATIONS);
    }

    @Test
    @DisplayName("recovery works for a handle acquired once at startup")
    void longHeldHandleRecovers() {
        // the access pattern of a service that wires its handle at boot; the library's recovery
        // path is only entered when the caller asks again
        assertContract(runWorkloadThrough(profile().primaryFault(), "LONG_HELD"), faultClearedAt,
                profile().thresholds(), MIN_OPERATIONS);
    }

    /** Whether this class runs the leak scenario; false where the same library is cycled elsewhere. */
    protected boolean checksResourceHygiene() {
        return true;
    }

    @Test
    @DisplayName("repeated failover leaves no threads or descriptors behind")
    void resourceHygiene() {
        assumeTrue(checksResourceHygiene(),
                "the leak lives in the client library, which another class already cycles");
        Thresholds thresholds = profile().thresholds();
        app.startWorkload(profile().probe(), "PER_CALL", profile().operationsPerSecond());
        letWorkloadRun(WARM_UP);
        Map<String, Object> baseline = app.diag();
        log.info("Leak baseline: {}", baseline);

        for (int cycle = 1; cycle <= thresholds.leakCycles(); cycle++) {
            log.info("Leak cycle {}/{}", cycle, thresholds.leakCycles());
            profile().primaryFault().injectAndAwaitRecovery(faults);
            letWorkloadRun(Duration.ofSeconds(10));
        }

        letWorkloadRun(QUIET_PERIOD);
        Map<String, Object> after = app.diag();
        log.info("Leak after {} cycles: {}", thresholds.leakCycles(), after);

        assertNoLeak(baseline, after, thresholds);
        assertNothingHung(app.stats(), thresholds);
    }

    /** Warm up, inject, let it settle, return the timeline. The shape every scenario shares. */
    protected WorkloadStats runWorkloadThrough(Fault fault, String handleMode) {
        app.startWorkload(profile().probe(), handleMode, profile().operationsPerSecond());
        letWorkloadRun(WARM_UP);
        faultClearedAt = fault.injectAndAwaitRecovery(faults);
        awaitRecovery();
        letWorkloadRun(QUIET_PERIOD);

        WorkloadStats stats = app.stats();
        log.info("{} / {}: {}", profile().probe(), fault, StorageAssertions.summarise(stats, faultClearedAt));
        return stats;
    }

    /**
     * Waits for the client to answer again rather than sitting out the whole recovery allowance,
     * which is what a scenario used to cost even when recovery took a second. A client that never
     * comes back falls through to the assertions, which report the timeline instead of a timeout.
     */
    private void awaitRecovery() {
        try {
            await("the client answers again")
                    .atMost(profile().thresholds().recovery().plus(RECOVERY_GRACE))
                    .pollInterval(Duration.ofSeconds(2))
                    .until(() -> StorageAssertions.firstSuccessAfter(app.stats(), faultClearedAt).isPresent());
        } catch (ConditionTimeoutException e) {
            log.warn("No successful operation within the recovery allowance; the assertions will report it");
        }
    }

    private static void letWorkloadRun(Duration duration) {
        await().pollDelay(duration).atMost(duration.plusSeconds(5)).until(() -> true);
    }
}
