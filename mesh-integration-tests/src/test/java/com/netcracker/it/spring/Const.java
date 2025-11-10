package com.netcracker.it.spring;

import java.util.Arrays;
import java.util.List;

import static com.netcracker.it.spring.utils.Utils.replaceOrEmpty;

public class Const {
    public static final String ORIGIN_NAMESPACE_ENV_NAME = "ORIGIN_NAMESPACE";
    public static final String ORIGIN_NAMESPACE = System.getProperty(ORIGIN_NAMESPACE_ENV_NAME);
    public static final String PEER_NAMESPACE_ENV_NAME = "PEER_NAMESPACE";
    public static final String PEER_NAMESPACE = System.getProperty(PEER_NAMESPACE_ENV_NAME);

    public static final String BASELINE_ORIGIN_NAMESPACE_PROPERTY = "BASELINE_ORIGIN_NAMESPACE";
    public static final String BASELINE_ORIGIN_NAMESPACE = System.getProperty(BASELINE_ORIGIN_NAMESPACE_PROPERTY);
    public static final String BASELINE_CONTROLLER_NAMESPACE_PROPERTY = "BASELINE_CONTROLLER_NAMESPACE";
    public static final String BASELINE_CONTROLLER_NAMESPACE = System.getProperty(BASELINE_CONTROLLER_NAMESPACE_PROPERTY);
    public static final String SATELLITE_ORIGIN_NAMESPACE_PROPERTY = "SATELLITE_ORIGIN_NAMESPACE";
    public static final String SATELLITE_ORIGIN_NAMESPACE = System.getProperty(SATELLITE_ORIGIN_NAMESPACE_PROPERTY);

    public static final String CONTROLLER_NAMESPACE_ENV_NAME = "env.cloud-namespace";
    public static final String CONTROLLER_NAMESPACE = System.getProperty(CONTROLLER_NAMESPACE_ENV_NAME);

    public static final String USE_DIRECT_TOKEN_PROPERTY = "USE_DIRECT_TOKEN";
    public static final String USE_DIRECT_TOKEN = System.getProperty(USE_DIRECT_TOKEN_PROPERTY, "false");
    public static final String SKIP_NODE_PORT_TESTS_PROPERTY = "SKIP_NODE_PORT_TESTS";
    public static final String SKIP_NODE_PORT_TESTS = System.getProperty(SKIP_NODE_PORT_TESTS_PROPERTY, "false");
    public static final String SKIP_BG_PLUGIN_TESTS_PROPERTY = "SKIP_BG_PLUGIN_TESTS";
    public static final String SKIP_BG_PLUGIN_TESTS = System.getProperty(SKIP_BG_PLUGIN_TESTS_PROPERTY, "false");
    public static final String BG_CONTEXT_CONFIG_MAP_NAME = "bluegreen-context";
    public static final String BG_CONTEXT_JSON_FIELD = "bluegreen-context.json";

    public static final String SATELLITE_ENV_NAME = "SATELLITE_NAMESPACE";
    public static final String SATELLITE = System.getProperty(SATELLITE_ENV_NAME);
    public static final String BG_PLUGIN_NAMESPACE_PROPERTY = "BG_PLUGIN_NAMESPACE";
    public static final String BG_PLUGIN_NAMESPACE = System.getProperty(BG_PLUGIN_NAMESPACE_PROPERTY);
    public static final String BG_PLUGIN_SERVICE_NAME = "bg-operator-plugin-example";

    public static final String DEPLOY_SATELLITE = System.getProperty("DEPLOY_SATELLITE");
    public static final String BG_OPERATOR_LOGIN = System.getProperty("BG_OPERATOR_LOGIN");
    public static final String BG_OPERATOR_PASSWORD = System.getProperty("BG_OPERATOR_PASSWORD");
    public static final String ENV_CLOUD_PUBLIC_HOST = System.getenv("ENV_CLOUD_PUBLIC_HOST");
    public static final String SPRING_SERVICE_NAME = "mesh-test-service-spring";
    public static final String QUARKUS_SERVICE_NAME = "mesh-test-service-quarkus";
    public static final String GO_SERVICE_NAME = "mesh-test-service-go";
    public static final String PUBLIC_GW_SERVICE_NAME = "public-gateway-service";
    public static final String PRIVATE_GW_SERVICE_NAME = "private-gateway-service";
    public static final String INTERNAL_GW_SERVICE_NAME = "internal-gateway-service";
    public static final String EDGE_ROUTER_SERVICE_NAME = "edge-router";
    public static final String EDGE_ROUTER_CONTROLLER_SERVICE_NAME = "edge-router-controller";
    public static final String BG_OPERATOR_SERVICE_NAME = "bg-operator";
    public static final String BG_NOTIFIER_SERVICE_NAME = "bg-notifier";
    public static final String PAAS_MEDIATION_SERVICE_NAME = "paas-mediation";
    public static final String TENANT_MANAGER_SERVICE_NAME = "tenant-manager";
    public static final List<String> CONTROLLER_SERVICES_NAMES = Arrays.asList(EDGE_ROUTER_SERVICE_NAME, EDGE_ROUTER_CONTROLLER_SERVICE_NAME, BG_OPERATOR_SERVICE_NAME, BG_NOTIFIER_SERVICE_NAME);
    public static final List<String> SATELLITE_CHECKING_SERVICES_NAMES = Arrays.asList(TENANT_MANAGER_SERVICE_NAME);
    public static final String INGRESS_GW_SERVICE_NAME = "test-ingress-gateway";
    public static final String INGRESS_2_GW_SERVICE_NAME = "test-ingress-gateway-2";
    public static final String INGRESS_GW_INGRESS_NAME_IN_CONTROLLER = "test-ingress-gateway-web-" + ORIGIN_NAMESPACE;
    public static final String INGRESS_GW_INGRESS_NAME = "test-ingress-gateway-web";
    public static final String INGRESS_2_GW_INGRESS_NAME_IN_CONTROLLER = "test-ingress-gateway-2-web-" + PEER_NAMESPACE;
    public static final String INGRESS_2_GW_INGRESS_NAME = "test-ingress-gateway-2-web";
    public static final String INGRESS_GW_INGRESS_NAME_FROM_INGRESS_IN_CONTROLLER = "test-ingress-gateway-from-ingress-" + ORIGIN_NAMESPACE;
    public static final String INGRESS_GW_INGRESS_NAME_FROM_INGRESS = "test-ingress-gateway-from-ingress";
    public static final String PUBLIC_GW_INGRESS_NAME = "public-gateway-" + ORIGIN_NAMESPACE;
    public static final String PRIVATE_GW_INGRESS_NAME = "private-gateway-" + ORIGIN_NAMESPACE;
    public static final String BG_OPERATOR_INGRESS_NAME = "bg-operator";
    public static final String GLOBAL_PROFILE = "global";
    public static final String X_VERSION_NAME_HEADER = "x-version-name";
    public static final String X_VERSION_HEADER = "x-version";
    public static final String X_VERSION_NAME_VALUE_ACTIVE = "active";
    public static final String X_VERSION_NAME_VALUE_CANDIDATE = "candidate";
    public static final String X_VERSION_NAME_VALUE_LEGACY = "legacy";
    public static final String X_VERSION_NAME_VALUE_UNKNOWN = "unknown";
    public static final String X_VERSION_VALUE_V99 = "v99";
    public static final String PROTOCOL_PREFIX = "http://";
    public static final String COMPLETED_STATUS = "completed";
    public static final String IN_PROGRESS_STATUS = "in progress";
    public static final String TEMINATED = "terminated";
    public static final String FAIL_STATUS = "failed";
    public static final String SERVICE_NAME = "mesh-test-service-spring";
    public static final String EGRESS_GW_SERVICE_NAME = "egress-gateway";
    public static final String BLUEGREEN_CONTROLLER_SECRET_NAME = "bluegreen-controller-credentials";
    public static final String BG_GET_METRICS_URL = "/q/metrics";
    public static final String SPRING_TO_QUARKUS_URL = "/api/v1/" + SPRING_SERVICE_NAME + "/hello/quarkus";
    public static final String GO_TO_SPRING_URL = "/api/v1/" + GO_SERVICE_NAME + "/hello/spring";
    public static final String GO_TO_SPRING_TCP_URL = "/api/v1/" + GO_SERVICE_NAME + "/proxy/tcp";
    public static final String QUARKUS_TO_GO_URL = "/api/v1/" + QUARKUS_SERVICE_NAME + "/hello/go";
    public static final String BG_PLUGIN_EXAMPLE_HISTORY = "api/v1/history";
    public static final String MESH_TEST_SERVICE_GO_V1 = "mesh-test-service-go-v1";
    public static final String MESH_TEST_SERVICE_GO_HPA = "mesh-test-service-go-hpa";
    public static final int HPA_TEST_COUNT_OF_REPLICAS = 4;
    public static final int HPA_TEST_COUNT_OF_REPLICAS_FOR_ROLLBACK = 5;
    public static final String MESH_TCP_NODE_PORT = "MESH_TCP_NODE_PORT";
    public static final String MESH_TCP_NODE_PORT_VALUE = getPropOrEnvWithDefault(MESH_TCP_NODE_PORT, "30036");
    public static final String NODE_IP_MAPPING = "NODE_IP_MAPPING";
    public static final String NODE_IP_MAPPING_VALUE = replaceOrEmpty(getPropOrEnv(NODE_IP_MAPPING), "\\s+", "");
    public static final String SERVICE_TYPE_LOAD_BALANCER = "LoadBalancer";

    public static final String K8S_SERVICE_TYPE_PROPERTY = "K8S_SERVICE_TYPE";
    public static final String K8S_SERVICE_TYPE_VALUE = replaceOrEmpty(getPropOrEnv(K8S_SERVICE_TYPE_PROPERTY), "\\s+", "");

    public static final String EXPECTED_CLOUD_PROPERTY = "EXPECTED_CLOUD";
    public static final String EXPECTED_CLOUD_VALUE = getPropOrEnvWithDefault(EXPECTED_CLOUD_PROPERTY, "testCloudName");
    public static final String EXPECTED_TENANT_PROPERTY = "EXPECTED_TENANT";
    public static final String EXPECTED_TENANT_VALUE = getPropOrEnvWithDefault(EXPECTED_TENANT_PROPERTY,  "testTenantName");
    public static final String EXPECTED_USERNAME_PROPERTY = "EXPECTED_USERNAME";
    public static final String EXPECTED_USERNAME_VALUE = getPropOrEnvWithDefault(EXPECTED_USERNAME_PROPERTY,  "testUsername");
    public static final String EXPECTED_EMAIL_PROPERTY = "EXPECTED_EMAIL";
    public static final String EXPECTED_EMAIL_VALUE = getPropOrEnvWithDefault(EXPECTED_EMAIL_PROPERTY,  "test@email.com");

    private static String getPropOrEnv(String name) {
        String value = System.getProperty(name);
        if (value == null) {
            value = System.getenv(name);
        }

        return value;
    }

    private static String getPropOrEnvWithDefault(String name, String defaultValue) {
        String value = getPropOrEnv(name);
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }

        return value;
    }
}
