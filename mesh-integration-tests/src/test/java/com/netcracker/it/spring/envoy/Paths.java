package com.netcracker.it.spring.envoy;

class Paths {
    static final String API_V_1_MESH_TEST_SERVICE_SPRING = "api/v1/mesh-test-service-spring/";
    static final String PROXY_PATH = API_V_1_MESH_TEST_SERVICE_SPRING + "spring/proxy?url=";
    static final String COMPOSITE_SERVICE_BASE_PATH = "mesh-test-service-spring:8080/";
    static final String INTERNAL_GATEWAY_BASE_PATH = "internal-gateway-service:8080/";
    static final String HELLO_PATH = API_V_1_MESH_TEST_SERVICE_SPRING + "hello";
    static final String HELLO_VIA_PROXY = PROXY_PATH + COMPOSITE_SERVICE_BASE_PATH + HELLO_PATH;
    static final String HELLO_VIA_INTERNAL_GATEWAY = PROXY_PATH + INTERNAL_GATEWAY_BASE_PATH + HELLO_PATH;
}
