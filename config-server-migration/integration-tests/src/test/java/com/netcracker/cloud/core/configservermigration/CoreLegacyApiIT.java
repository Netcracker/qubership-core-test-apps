package com.netcracker.cloud.core.configservermigration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Checks that hold wherever core-legacy-api serves the Service config-server, installed from the start or migrated to.
 * The data is written under an application of its own, which {@link AfterMigrationIT} does not compare.
 */
@EnableExtension
@Tag("core-legacy-api")
class CoreLegacyApiIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String APPLICATION = "core-legacy-api-it";

    /** A default core-legacy-api adds to global on startup when it is missing. */
    private static final String GLOBAL_DEFAULT = "apigateway.internal.url";
    /** The Consul name of global. */
    private static final String GLOBAL_KV_APPLICATION = "application";

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

    @BeforeAll
    void connect() {
        Cluster.requireCoreLegacyApiInPlaceOfConfigServer(kubernetes);
        coreLegacyApi = new Http(coreLegacyApiUrl);
        consul = new Http(consulUrl);
        coreLegacyApi.awaitOk("core-legacy-api", "/applications");
    }

    @Test
    void aGlobalDefaultIsServedAndStored() throws IOException {
        String served = servedValue("global", "default", GLOBAL_DEFAULT);

        assertNotNull(served, GLOBAL_DEFAULT + " in GET /global/default");
        assertEquals(served, storedValue(GLOBAL_KV_APPLICATION, GLOBAL_DEFAULT), GLOBAL_DEFAULT + " in Consul");
    }

    @TestFactory
    Stream<DynamicTest> aWriteReachesTheApiAndConsul() {
        return Stream.of(
                new Write("a posted property is served and stored", "post", "posted.key", "posted",
                        new Request(Method.POST, "", Map.of("posted.key", "posted"), 201)),
                new Write("a put property replaces the stored value", "put", "db.pool.size", "30",
                        new Request(Method.POST, "", Map.of("db.pool.size", "10"), 201),
                        new Request(Method.PUT, "", Map.of("db.pool.size", "30"), 201)),
                new Write("a deleted property is gone", "delete", "banner", null,
                        new Request(Method.POST, "", Map.of("banner", "line one\nline two"), 201),
                        new Request(Method.POST, "/properties-delete", List.of("banner"), 200))
        ).map(write -> DynamicTest.dynamicTest(write.name(), () -> {
            String path = "/" + APPLICATION + "/" + write.profile();
            for (Request request : write.requests()) {
                String target = path + request.pathSuffix();
                Http.Response response = switch (request.method()) {
                    case POST -> coreLegacyApi.post(target, request.body());
                    case PUT -> coreLegacyApi.put(target, request.body());
                };
                assertEquals(request.status(), response.status(), request.method() + " " + target);
            }

            assertAll(
                    () -> assertEquals(write.expected(), servedValue(APPLICATION, write.profile(), write.key()),
                            write.key() + " in GET " + path),
                    () -> assertEquals(write.expected(), storedValue(APPLICATION + "," + write.profile(), write.key()),
                            write.key() + " in Consul"));
        }));
    }

    /** Requests to one profile, and the value they leave for {@code key}: {@code null} for none. */
    private record Write(String name, String profile, String key, String expected, Request... requests) {
    }

    private record Request(Method method, String pathSuffix, Object body, int status) {
    }

    private enum Method { POST, PUT }

    /** The value of the source with the highest precedence that has the key, or {@code null} if none has it. */
    private String servedValue(String application, String profile, String key) throws IOException {
        JsonNode environment = JSON.readTree(coreLegacyApi.get("/" + application + "/" + profile).body());
        for (JsonNode source : environment.get("propertySources")) {
            JsonNode value = source.get("source").get(key);
            if (value != null) {
                return value.asText();
            }
        }
        return null;
    }

    /** The value Consul stores for the key, or {@code null} if it has none. */
    private String storedValue(String kvApplication, String key) {
        String path = "/v1/kv/" + Snapshot.kvPrefix() + kvApplication + "/" + key.replace('.', '/') + "?raw";
        Http.Response response = consul.get(path);
        if (response.status() == 404) {
            return null;
        }
        assertEquals(200, response.status(), "GET " + path);
        return response.body();
    }
}
