package com.netcracker.cloud.core.configservermigration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.netcracker.cloud.core.configservermigration.stand.Cluster;
import com.netcracker.cloud.core.configservermigration.stand.Http;
import com.netcracker.cloud.core.configservermigration.stand.ResponseForms;
import com.netcracker.cloud.core.configservermigration.stand.Snapshot;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.LocalPortForward;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static com.netcracker.cloud.core.configservermigration.MigrationData.APPLICATION;
import static com.netcracker.cloud.core.configservermigration.MigrationData.CHANGED_DEFAULT_KV_KEY;
import static com.netcracker.cloud.core.configservermigration.MigrationData.CHANGED_DEFAULT_VALUE;
import static com.netcracker.cloud.core.configservermigration.MigrationData.REQUESTS;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The after-migration phase, run after config-server is uninstalled and core-legacy-api is installed on the same
 * Consul: core-legacy-api keeps what config-server stored, answers the baseline requests as config-server did, and
 * stores new writes.
 *
 * <p>The answers and the KV are read once, before any test writes, so the comparisons do not depend on the order
 * the tests run in. A difference the migration guide allows is normalized in {@link ResponseForms}; any other
 * difference fails, and becomes allowed only once the guide lists it.
 */
class AfterMigrationIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static KubernetesClient kubernetes;
    private static LocalPortForward coreLegacyApiPort;
    private static LocalPortForward consulPort;
    private static Http coreLegacyApi;
    private static Http consul;
    private static Snapshot baseline;
    private static Snapshot migrated;

    @BeforeAll
    static void readBothSnapshots() {
        kubernetes = Cluster.newClient();
        Cluster.requireCoreLegacyApiInPlaceOfConfigServer(kubernetes);
        coreLegacyApiPort = Cluster.forwardConfigServerPort(kubernetes);
        consulPort = Cluster.forwardConsulPort(kubernetes);
        coreLegacyApi = new Http(Cluster.url(coreLegacyApiPort));
        consul = new Http(Cluster.url(consulPort));
        await("core-legacy-api answers GET /applications")
                .atMost(Duration.ofMinutes(2))
                .pollInterval(Duration.ofSeconds(2))
                .ignoreExceptions()
                .until(() -> coreLegacyApi.get("/applications").status() == 200);
        baseline = Snapshot.fromJson(Cluster.loadBaseline(kubernetes));
        migrated = Snapshot.take(coreLegacyApi, consul, REQUESTS);
    }

    @AfterAll
    static void disconnect() {
        Cluster.tearDown(kubernetes, coreLegacyApiPort, consulPort);
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

    @Test
    void aPostedPropertyIsServedAndStored() {
        assertEquals(201, coreLegacyApi.post("/" + APPLICATION + "/dev", Map.of("after.post", "posted")).status(),
                "POST /" + APPLICATION + "/dev");

        assertAll(
                () -> assertEquals("posted", servedValue("after.post"), "after.post in GET /" + APPLICATION + "/dev"),
                () -> assertEquals(new Http.Response(200, "posted"), storedValue("after/post"),
                        "after.post in Consul"));
    }

    @Test
    void aPutPropertyReplacesTheStoredValue() {
        assertEquals(201, coreLegacyApi.put("/" + APPLICATION + "/dev", Map.of("db.pool.size", "30")).status(),
                "PUT /" + APPLICATION + "/dev");

        assertAll(
                () -> assertEquals("30", servedValue("db.pool.size"), "db.pool.size in GET /" + APPLICATION + "/dev"),
                () -> assertEquals(new Http.Response(200, "30"), storedValue("db/pool/size"),
                        "db.pool.size in Consul"));
    }

    @Test
    void aDeletedPropertyIsGoneFromTheApiAndFromConsul() {
        assertEquals(200, coreLegacyApi.post("/" + APPLICATION + "/dev/properties-delete", List.of("banner")).status(),
                "POST /" + APPLICATION + "/dev/properties-delete");

        assertAll(
                () -> assertNull(servedValue("banner"), "banner in GET /" + APPLICATION + "/dev"),
                () -> assertEquals(404, storedValue("banner").status(), "banner in Consul"));
    }

    /** The value of the source with the highest precedence that has the key, or {@code null} if none has it. */
    private static String servedValue(String key) throws IOException {
        JsonNode environment = JSON.readTree(coreLegacyApi.get("/" + APPLICATION + "/dev").body());
        for (JsonNode source : environment.get("propertySources")) {
            JsonNode value = source.get("source").get(key);
            if (value != null) {
                return value.asText();
            }
        }
        return null;
    }

    private static Http.Response storedValue(String keyPath) {
        return consul.get("/v1/kv/" + Snapshot.kvPrefix() + APPLICATION + ",dev/" + keyPath + "?raw");
    }
}
