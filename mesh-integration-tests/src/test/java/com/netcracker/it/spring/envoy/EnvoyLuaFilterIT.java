package com.netcracker.it.spring.envoy;

import com.google.gson.Gson;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.EnableExtension;
import com.netcracker.it.spring.model.LuaFilterTraceResponse;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Response;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static com.netcracker.it.spring.envoy.Paths.API_V_1_MESH_TEST_SERVICE_SPRING;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@EnableExtension
@Slf4j
@Tag("LuaFilter")
class EnvoyLuaFilterIT extends BaseTest {

    private static final String LUA_FILTER_PATH = API_V_1_MESH_TEST_SERVICE_SPRING + "lua-filter/";
    private static final String X_UUID = "X-Uuid";
    // Lua filters reach the gateways asynchronously after the chart is installed
    private static final Duration READY_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(2);

    static Stream<Arguments> luaFilterGateways() throws MalformedURLException, URISyntaxException {
        return Stream.concat(
                Stream.of(Arguments.of("public-gateway", publicGWServerUrl)),
                internalGatewayViaSpringProxy());
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("luaFilterGateways")
    void testLuaFilterAddsUuidHeader(String gatewayName, URL baseUrl) {
        String uuid = UUID.randomUUID().toString();
        String path = LUA_FILTER_PATH + uuid;

        await("Lua filter on " + gatewayName)
                .atMost(READY_TIMEOUT)
                .pollInterval(POLL_INTERVAL)
                .ignoreExceptions()
                .untilAsserted(() -> {
                    try (Response response = executeGetRequest(baseUrl, path, Map.of())) {
                        String body = response.body().string();
                        assertEquals(200, response.code(), "%s: unexpected status, body: %s".formatted(gatewayName, body));
                        LuaFilterTraceResponse trace = new Gson().fromJson(body, LuaFilterTraceResponse.class);
                        assertNotNull(trace, "%s: empty trace body".formatted(gatewayName));
                        assertEquals("/" + path, trace.getPath());
                        assertEquals(uuid, trace.getHeader(X_UUID),
                                "%s: Lua filter should add %s from the path".formatted(gatewayName, X_UUID));
                    }
                });
    }
}
