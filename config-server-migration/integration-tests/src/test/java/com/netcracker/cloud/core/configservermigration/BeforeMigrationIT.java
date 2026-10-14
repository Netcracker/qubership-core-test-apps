package com.netcracker.cloud.core.configservermigration;

import com.netcracker.cloud.core.configservermigration.stand.Cluster;
import com.netcracker.cloud.core.configservermigration.stand.Http;
import com.netcracker.cloud.core.configservermigration.stand.Snapshot;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.LocalPortForward;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static com.netcracker.cloud.core.configservermigration.MigrationData.APPLICATION;
import static com.netcracker.cloud.core.configservermigration.MigrationData.APPLICATION_DEFAULT;
import static com.netcracker.cloud.core.configservermigration.MigrationData.APPLICATION_DEV;
import static com.netcracker.cloud.core.configservermigration.MigrationData.CHANGED_DEFAULT_KV_KEY;
import static com.netcracker.cloud.core.configservermigration.MigrationData.CHANGED_DEFAULT_VALUE;
import static com.netcracker.cloud.core.configservermigration.MigrationData.DEV_GREETING_KV_KEY;
import static com.netcracker.cloud.core.configservermigration.MigrationData.GLOBAL_DEFAULT;
import static com.netcracker.cloud.core.configservermigration.MigrationData.REQUESTS;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The before-migration phase, run against config-server on Consul: writes {@link MigrationData} through
 * config-server and records its answers and the Consul KV as the baseline {@link AfterMigrationIT} compares
 * core-legacy-api with.
 *
 * <p>A failure here leaves no baseline, and {@link AfterMigrationIT} then fails on every check.
 */
class BeforeMigrationIT {

    private static KubernetesClient kubernetes;
    private static LocalPortForward configServerPort;
    private static LocalPortForward consulPort;
    private static Http configServer;
    private static Http consul;

    @BeforeAll
    static void connect() {
        kubernetes = Cluster.newClient();
        Cluster.deleteBaseline(kubernetes);
        configServerPort = Cluster.forwardConfigServerPort(kubernetes);
        consulPort = Cluster.forwardConsulPort(kubernetes);
        configServer = new Http(Cluster.url(configServerPort));
        consul = new Http(Cluster.url(consulPort));
        await("config-server answers GET /applications")
                .atMost(Duration.ofMinutes(2))
                .pollInterval(Duration.ofSeconds(2))
                .ignoreExceptions()
                .until(() -> configServer.get("/applications").status() == 200);
    }

    @AfterAll
    static void disconnect() {
        Cluster.tearDown(kubernetes, configServerPort, consulPort);
    }

    /**
     * The KV checks guard the baseline itself: a config-server that stores properties anywhere but Consul would
     * leave nothing there for core-legacy-api to keep, and the KV check of {@link AfterMigrationIT} would pass on
     * nothing.
     */
    @Test
    void configServerStoresTheDataInConsulAndItsAnswersAreRecorded() {
        assertEquals(201, configServer.post("/global/default", GLOBAL_DEFAULT).status(), "POST /global/default");
        assertEquals(201, configServer.post("/" + APPLICATION + "/default", APPLICATION_DEFAULT).status(),
                "POST /" + APPLICATION + "/default");
        assertEquals(201, configServer.put("/" + APPLICATION + "/dev", APPLICATION_DEV).status(),
                "PUT /" + APPLICATION + "/dev");

        Snapshot baseline = Snapshot.take(configServer, consul, REQUESTS);

        assertAll(
                () -> assertEquals(CHANGED_DEFAULT_VALUE, baseline.kv().get(CHANGED_DEFAULT_KV_KEY),
                        CHANGED_DEFAULT_KV_KEY),
                () -> assertEquals(APPLICATION_DEV.get("greeting"), baseline.kv().get(DEV_GREETING_KV_KEY),
                        DEV_GREETING_KV_KEY));
        Cluster.saveBaseline(kubernetes, baseline.toJson());
    }
}
