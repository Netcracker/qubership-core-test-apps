package com.netcracker.cloud.core.configservermigration;

import com.netcracker.cloud.core.configservermigration.stand.Cluster;
import com.netcracker.cloud.core.configservermigration.stand.Http;
import com.netcracker.cloud.core.configservermigration.stand.Snapshot;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.Cloud;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.EnableExtension;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.IntValue;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.PortForward;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.Value;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.URL;

import static com.netcracker.cloud.core.configservermigration.MigrationData.APPLICATION;
import static com.netcracker.cloud.core.configservermigration.MigrationData.APPLICATION_DEFAULT;
import static com.netcracker.cloud.core.configservermigration.MigrationData.APPLICATION_DEV;
import static com.netcracker.cloud.core.configservermigration.MigrationData.CHANGED_DEFAULT_KV_KEY;
import static com.netcracker.cloud.core.configservermigration.MigrationData.CHANGED_DEFAULT_VALUE;
import static com.netcracker.cloud.core.configservermigration.MigrationData.DEV_GREETING_KV_KEY;
import static com.netcracker.cloud.core.configservermigration.MigrationData.GLOBAL_DEFAULT;
import static com.netcracker.cloud.core.configservermigration.MigrationData.REQUESTS;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The before-migration phase, run against config-server on Consul: writes {@link MigrationData} through
 * config-server and records its answers and the Consul KV as the baseline {@link AfterMigrationIT} compares
 * core-legacy-api with.
 *
 * <p>A failure here leaves no baseline, and {@link AfterMigrationIT} then fails on every check.
 */
@EnableExtension
@Tag("migration")
class BeforeMigrationIT {

    @Cloud(namespace = @Value(Cluster.CORE_NAMESPACE))
    private KubernetesClient kubernetes;

    @PortForward(serviceName = @Value(Cluster.CONFIG_SERVER),
            cloud = @Cloud(namespace = @Value(Cluster.CORE_NAMESPACE)))
    private URL configServerUrl;

    @PortForward(serviceName = @Value(Cluster.CONSUL_SERVER), port = @IntValue(8500),
            cloud = @Cloud(namespace = @Value(Cluster.CONSUL_NAMESPACE)))
    private URL consulUrl;

    private Http configServer;
    private Http consul;

    @BeforeAll
    void connect() {
        Cluster.deleteBaseline(kubernetes);
        configServer = new Http(configServerUrl);
        consul = new Http(consulUrl);
        configServer.awaitOk(Cluster.CONFIG_SERVER, "/applications");
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
