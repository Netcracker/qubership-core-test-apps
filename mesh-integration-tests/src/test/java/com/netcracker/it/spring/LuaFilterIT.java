package com.netcracker.it.spring;

import com.netcracker.cloud.junit.cloudcore.extension.annotations.Cloud;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.EnableExtension;
import com.netcracker.it.spring.lua.LuaFilterTestHelper;
import com.netcracker.it.spring.model.LuaFilterTraceResponse;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@Slf4j
@EnableExtension
@Tag("LuaFilter")
public class LuaFilterIT {

    private static final String ROUTE_PREFIX = "/api/v1/mesh-test-service-spring/lua-filter/";
    private static final String TEST_UUID = "a1b2c3d4-e5f6-7890-1234-567890abcdef";

    @Cloud
    protected static KubernetesClient kubernetesClient;

    @BeforeAll
    public static void setUp() throws Exception {
        IntegrationTestSupport.init(kubernetesClient);
        if (!IntegrationTestSupport.isExecutorAvailable()) {
            log.warn("Skipping: mesh-test-service-spring pod not found — Lua filter IT requires EXEC_IN_POD executor");
            Assumptions.assumeTrue(false, "mesh-test-service-spring pod not found — skipping Lua filter IT");
        }
    }

    @ParameterizedTest(name = "Lua filter via {0} adds X-Uuid")
    @MethodSource("gateways")
    void luaFilter_addsUuidHeader(String label, String gatewayServiceName) throws Exception {
        LuaFilterTraceResponse response = LuaFilterTestHelper.waitUntilGatewayReady(gatewayServiceName, ROUTE_PREFIX + TEST_UUID);
        assertUuidHeader(response);
    }

    private static Stream<Arguments> gateways() {
        return Stream.of(
                Arguments.of("internal-gateway-service", Const.INTERNAL_GW_SERVICE_NAME),
                Arguments.of("public-gateway-service", Const.PUBLIC_GW_SERVICE_NAME)
        );
    }

    private void assertUuidHeader(LuaFilterTraceResponse response) {
        assertNotNull(response, "trace response body");
        assertEquals(ROUTE_PREFIX + TEST_UUID, response.getPath());
        assertEquals(TEST_UUID, response.getHeader("X-Uuid"));
    }
}
