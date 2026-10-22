package com.netcracker.cloud.core.configservermigration;

import com.netcracker.cloud.core.configservermigration.stand.Cluster;
import com.netcracker.cloud.core.configservermigration.stand.Http;
import com.netcracker.cloud.core.configservermigration.stand.ResponseForms;
import com.netcracker.cloud.core.configservermigration.stand.Snapshot;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.Cloud;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.EnableExtension;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.IntValue;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.PortForward;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.Value;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static com.netcracker.cloud.core.configservermigration.MigrationData.CHANGED_DEFAULT_KV_KEY;
import static com.netcracker.cloud.core.configservermigration.MigrationData.CHANGED_DEFAULT_VALUE;
import static com.netcracker.cloud.core.configservermigration.MigrationData.REQUESTS;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The after-migration phase, run after config-server is uninstalled and core-legacy-api is installed on the same
 * Consul: core-legacy-api keeps what config-server stored and answers the baseline requests as config-server did.
 *
 * <p>The answers and the KV are read once, before any test writes, so the comparisons do not depend on the order
 * the tests run in. A difference the migration guide allows is normalized in {@link ResponseForms}; any other
 * difference fails, and becomes allowed only once the guide lists it.
 */
@EnableExtension
@Tag("migration")
class AfterMigrationIT {

    @Cloud(namespace = @Value(Cluster.CORE_NAMESPACE))
    private KubernetesClient kubernetes;

    @PortForward(serviceName = @Value(Cluster.CONFIG_SERVER),
            cloud = @Cloud(namespace = @Value(Cluster.CORE_NAMESPACE)))
    private URL coreLegacyApiUrl;

    @PortForward(serviceName = @Value(Cluster.CONSUL_SERVER), port = @IntValue(8500),
            cloud = @Cloud(namespace = @Value(Cluster.CONSUL_NAMESPACE)))
    private URL consulUrl;

    private Http coreLegacyApi;
    private Http consul;
    private Snapshot baseline;
    private Snapshot migrated;

    @BeforeAll
    void readBothSnapshots() {
        Cluster.requireCoreLegacyApiInPlaceOfConfigServer(kubernetes);
        coreLegacyApi = new Http(coreLegacyApiUrl);
        consul = new Http(consulUrl);
        coreLegacyApi.awaitOk("core-legacy-api", "/applications");
        baseline = Snapshot.fromJson(Cluster.loadBaseline(kubernetes));
        migrated = Snapshot.take(coreLegacyApi, consul, REQUESTS);
    }

    /**
     * Keys core-legacy-api added are allowed here: they are defaults config-server did not write. The test prints
     * them.
     */
    @Test
    void consulKeepsEveryValueConfigServerStored() {
        List<String> differences = new ArrayList<>();
        for (Map.Entry<String, String> stored : baseline.kv().entrySet()) {
            String now = migrated.kv().get(stored.getKey());
            if (now == null) {
                differences.add(stored.getKey() + " is missing");
            } else if (!now.equals(stored.getValue())) {
                differences.add(stored.getKey() + ": expected <" + stored.getValue() + "> but was <" + now + ">");
            }
        }
        System.out.println("Keys core-legacy-api added: " + migrated.kv().keySet().stream()
                .filter(key -> !baseline.kv().containsKey(key))
                .toList());

        assertEquals(List.of(), differences, "keys under " + Snapshot.kvPrefix() + " config-server stored");
    }

    @Test
    void theDefaultChangedThroughConfigServerKeepsItsValue() {
        assertEquals(CHANGED_DEFAULT_VALUE, migrated.kv().get(CHANGED_DEFAULT_KV_KEY), CHANGED_DEFAULT_KV_KEY);
    }

    @TestFactory
    Stream<DynamicTest> coreLegacyApiAnswersAsConfigServerDid() {
        return REQUESTS.stream().map(request -> DynamicTest.dynamicTest("GET " + request, () -> {
            Http.Response expected = baseline.responses().get(request);
            Http.Response actual = migrated.responses().get(request);
            assertEquals(expected.status(), actual.status(), "status of GET " + request);
            assertEquals(ResponseForms.comparable(request, expected.body()),
                    ResponseForms.comparable(request, actual.body()), "body of GET " + request);
        }));
    }
}
