package com.netcracker.it.meshtestservicespring;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.google.gson.Gson;
import com.netcracker.cloud.security.core.utils.tls.TlsUtils;
import com.netcracker.it.meshtestservicespring.model.*;
import com.netcracker.it.meshtestservicespring.utils.TCPUtils;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentList;
import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.netcracker.it.meshtestservicespring.Const.*;
import static com.netcracker.it.meshtestservicespring.model.StateName.ACTIVE;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.*;

@Slf4j
public class CommonOperations {
    protected static final ObjectMapper objectMapperIndent = initObjectMapperIndent();
    protected static final ObjectMapper yamlObjectMapper = new ObjectMapper(new YAMLFactory());
    public static final MediaType JSON
            = MediaType.parse("application/json; charset=utf-8");
    public static final MediaType TEXT
            = MediaType.parse("text/plain; charset=utf-8");

    private static final ObjectMapper objectMapper = new ObjectMapper();

    public static final int DEFAULT_WAIT_TIMEOUT_IN_SECONDS = 300;
    public static final int POLLING_DELAY_IN_SECONDS = 5;

    public static final int ENVOY_ADMIN_PORT = 9901;

    public static final int TIMEOUT = 10 * 60 * 1000;

    private static String BG_OPERATOR_INGRESS_HOST;

    private static String BG_OPERATOR_CREDS;

    public static BGContext getBGContext(KubernetesClient k8sClient) throws JsonProcessingException {
        final ConfigMap configMap = k8sClient.configMaps()
                .inNamespace(CONTROLLER_NAMESPACE)
                .withName(BG_CONTEXT_CONFIG_MAP_NAME)
                .get();
        if (configMap == null) {
            log.info("ConfigMap {} not found in {}", BG_CONTEXT_CONFIG_MAP_NAME, CONTROLLER_NAMESPACE);
            return null;
        }
        final String json = configMap.getData().get(BG_CONTEXT_JSON_FIELD);

        BGContext bgContext = objectMapper.readValue(json, BGContext.class);
        log.info("Parsed {} configMap content: {}", BG_CONTEXT_CONFIG_MAP_NAME, bgContext);
        return bgContext;
    }

    public static OkHttpClient okHttpClient = new OkHttpClient.Builder()
            .addInterceptor(withRetryOnServiceUnavailableOrTimeout())
            .readTimeout(60, TimeUnit.SECONDS)
            .connectTimeout(60, TimeUnit.SECONDS)
            .sslSocketFactory(TlsUtils.getSslContext().getSocketFactory(), TlsUtils.getTrustManager())
            .build();

    private static Interceptor withRetryOnServiceUnavailableOrTimeout() {
        return chain -> {
            Request request = chain.request();
            Response response = null;
            java.io.InterruptedIOException ex = null;

            long deadline = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(2);
            while (System.currentTimeMillis() < deadline) {
                closeResponse(response);
                try {
                    response = chain.proceed(request);
                    log.info("response code {} for request to {} {}", response.code(), request.method(), request.url());
                    ex = null;
                    if (!isRetryableFailureStatusCode(response)) {
                        return response;
                    }
                } catch (java.io.InterruptedIOException e) {
                    log.error("Got Interrupted IO Exception for request to {}", request.url(), ex);
                    ex = e;
                }
                sleepForSeconds(3);
            }

            if (ex != null) {
                throw ex;
            }

            return response;
        };
    }

    private static boolean isRetryableFailureStatusCode(Response response) {
        return response == null || response.code() == 503
                || response.code() == 521
                || response.code() == 504;
    }

    private static void sleepForSeconds(int i) {
        try {
            TimeUnit.SECONDS.sleep(3);
        } catch (InterruptedException intEx) {
            log.warn("Interrupted during sleep", intEx);
            throw new RuntimeException("Interrupted during sleep", intEx);
        }
    }

    private static void closeResponse(Response response) {
        if (response != null) {
            try {
                response.close();
            } catch (Exception e1) {
                log.warn("Cannot close response", e1);
            }
        }
    }

    public static void validateNodePorts(KubernetesClient platformClient, String namespace, URL gateway) throws Exception {
        log.info("Test node ports on '{}' namespace", namespace);
        String springNodeIp = getNodeIp(platformClient, namespace, "mesh-test-service-spring-v1");

        TraceResponse traceResponse = sendPostTextRequest(gateway + GO_TO_SPRING_TCP_URL, springNodeIp + ":" + MESH_TCP_NODE_PORT_VALUE, 200);
        log.info("Trace response '{}'", traceResponse);

        assertNotNull(traceResponse);
        assertEquals("Hello from go", traceResponse.getRequestMessage());
        assertEquals(namespace, traceResponse.getNamespace());
        String traceIp = traceResponse.getRequestHost().replace("/", "").split(":")[0];
        assertTrue(TCPUtils.getNodeIPs().contains(traceIp));
    }

    private static String getNodeIp(KubernetesClient platformClient, String namespace, String serviceName) {
        PodList podList = platformClient.pods().inNamespace(namespace).withLabel("name", serviceName).list();
        assertFalse(podList.getItems().isEmpty());
        Optional<Pod> optionalPod = podList.getItems().stream().findFirst();
        assertTrue(optionalPod.isPresent());

        Pod pod = optionalPod.get();
        String nodeName = pod.getSpec().getNodeName();
        log.info("Found pod '{}' on '{}' node", pod.getMetadata().getName(), nodeName);
        String nodeIp = TCPUtils.getNodeIp(nodeName);
        log.info("Found nodeIp '{}'", nodeIp);
        assertNotNull(nodeIp, String.format("Can not found node ip for '%s' node", nodeName));

        return nodeIp;
    }

    public static TraceResponse sendPostTextRequest(String url, String body, int expectedCode) throws IOException {
        return sendRequest(url, "POST", body, TEXT, expectedCode);
    }

    public static TraceResponse sendRequest(String url, String method, String body, MediaType type, int expectedCode) throws IOException {
        RequestBody requestBody = null;
        if (body != null) {
            requestBody = RequestBody.create(body, type);
        }
        Request request = new Request.Builder()
                .url(url)
//                .addHeader("Authorization", "Bearer " + token)
                .method(method, requestBody)
                .build();
        try (Response response = okHttpClient.newCall(request).execute()) {
            assertEquals(expectedCode, response.code());
            String jsonResponse = response.body().string();
            return new Gson().fromJson(jsonResponse, TraceResponse.class);
        }
    }

    public static String getFileAsString(String filename) {
        final InputStream is = CommonOperations.class.getClassLoader().getResourceAsStream(filename);
        assert is != null;
        return new Scanner(is, "UTF-8").useDelimiter("\\A").next();
    }

    public static <T> T getEntityFromYamlFile(String filename, Map<String, String> replceMap, Class<T> clazz) {
        String line = getFileAsString(filename);
        for (Map.Entry<String, String> entry : replceMap.entrySet()) {
            line = line.replace("{{%s}}".formatted(entry.getKey()), entry.getValue());
        }
        try {
            return yamlObjectMapper.readValue(line, clazz);
        } catch (JsonProcessingException e) {
            log.error("Can not parse yaml to {} class", clazz);
            throw new RuntimeException(e);
        }
    }

    public static String getEnvoyConfigAsString(URL gatewayUrl) throws Exception {
        Request request = new Request.Builder()
                .url(gatewayUrl + "config_dump")
                .get()
                .build();
        log.info("Running HTTP request: {}", request);
        try (Response response = okHttpClient.newCall(request).execute()) {
            assertEquals(200, response.code());
            assert response.body() != null;
            return response.body().string();
        }
    }

    public static void validateConfig(Supplier<Boolean> validation, long timeout) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout;

        boolean validationPassed = false;
        while (System.currentTimeMillis() < deadline) {
            validationPassed = validation.get();
            if (validationPassed) {
                break;
            }
            Thread.sleep(5 * 1000);
        }
        assertTrue(validationPassed, "Wasm filter is not ready in time");
    }

    public static void validateByKey(URL gateway, String key) throws InterruptedException {
        validateConfig(
                () -> {
                    Pattern pattern = Pattern.compile("(.*)" + key + "(.*)");
                    String envoyRoutes;
                    try {
                        envoyRoutes = CommonOperations.getEnvoyConfigAsString(gateway);
                        assertThat(envoyRoutes, notNullValue());
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                    java.util.regex.Matcher matcher = pattern.matcher(envoyRoutes);
                    return matcher.find();
                },
                (TIMEOUT)
        );
    }

    public static void testIngressWithHeader(String ingressUrl, int expectedCode, String expectedNamespace, String expectedXVersion, String headerXVersionNameValue) throws IOException {
        testIngressWithHeader(ingressUrl, expectedCode, expectedNamespace, expectedXVersion, SPRING_SERVICE_NAME, headerXVersionNameValue, "");
    }

    public static void testIngressWithHeader(String ingressUrl, int expectedCode, String expectedNamespace, String expectedXVersion, String expectedService, String headerXVersionNameValue, String requestEnding) throws IOException {
        testRequestWithHeader(
                ingressUrl + "/api/v1/" + SPRING_SERVICE_NAME + "/hello" + requestEnding,
                expectedCode,
                expectedNamespace,
                expectedXVersion,
                expectedService,
                headerXVersionNameValue
        );
    }

    public static void testRequestWithHeader(String url, int expectedCode, String expectedNamespace, String expectedXVersion, String expectedService, String headerXVersionNameValue) {
        Request request = new Request.Builder()
                .url(url)
                .addHeader(X_VERSION_NAME_HEADER, headerXVersionNameValue)
                .addHeader(X_VERSION_HEADER, X_VERSION_VALUE_V99)
                .get()
                .build();
        try (Response response = okHttpClient.newCall(request).execute()) {
            assertEquals(expectedCode, response.code());
            if (response.code() != 401 && response.code() != 400 && response.code() != 403 && response.code() != 404) {
                assert response.body() != null;
                String jsonResponse = response.body().string();
                TraceResponse traceResponse = new Gson().fromJson(jsonResponse, TraceResponse.class);
                assertEquals(expectedService, traceResponse.getFamilyName());
                assertEquals(expectedNamespace, traceResponse.getNamespace());
                assertEquals(expectedXVersion, traceResponse.getXversion());
                assertEquals(headerXVersionNameValue, traceResponse.getXVersionName());
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void testIngressWithoutHeader(String url, int expectedCode, String expectedNamespace) throws IOException {
        testIngressWithoutHeader(url, expectedCode, expectedNamespace, SPRING_SERVICE_NAME, "");
    }

    public static void testIngressWithoutHeader(String ingressUrl, int expectedCode, String expectedNamespace, String expectedService, String requestEnding) throws IOException {
        testRequestWithoutHeader(
                ingressUrl + "/api/v1/" + SPRING_SERVICE_NAME + "/hello" + requestEnding,
                expectedCode,
                expectedNamespace,
                expectedService
        );
    }

    public static void testRequestWithoutHeader(String url, int expectedCode, String expectedNamespace, String expectedService) throws IOException {
        testRequestWithoutHeader(url, expectedCode, expectedNamespace, expectedService, true);
    }

    public static void testRequestWithoutHeader(String url, int expectedCode, String expectedNamespace, String expectedService, boolean checkXVersionAbsence) {
        Request request = new Request.Builder()
                .url(url)
                .addHeader(X_VERSION_HEADER, X_VERSION_VALUE_V99)
                .get()
                .build();
        try (Response response = okHttpClient.newCall(request).execute()) {
            assertEquals(expectedCode, response.code());
            if (response.code() != 401 && response.code() != 400 && response.code() != 403 && response.code() != 404) {
                assert response.body() != null;
                String jsonResponse = response.body().string();
                TraceResponse traceResponse = new Gson().fromJson(jsonResponse, TraceResponse.class);
                assertEquals(expectedService, traceResponse.getFamilyName());
                assertEquals(expectedNamespace, traceResponse.getNamespace());
                if (checkXVersionAbsence) {
                    assertTrue(traceResponse.getXversion() == null || traceResponse.getXversion().isBlank());
                    assertTrue(traceResponse.getXVersionName() == null || traceResponse.getXVersionName().isBlank());
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static String getActiveNamespace(KubernetesClient platformClient) throws IOException {
        log.info("getActiveNamespace");
        String bgOperatorHost = getBgOperatorHost(platformClient);
        String bgCreds = getBGOperatorCredentials(platformClient);
        BGContextResponse bgContextResponse = getBGOperationStatus(PROTOCOL_PREFIX + bgOperatorHost, bgCreds);
        NamespaceState originNamespace = bgContextResponse.getBGContext().getBGState().getOriginNamespace();
        if (ACTIVE.getName().equals(originNamespace.getState())) {
            return originNamespace.getName();
        } else {
            return bgContextResponse.getBGContext().getBGState().getPeerNamespace().getName();
        }
    }

    public static BGContextResponse getBGOperationStatus(KubernetesClient platformClient) throws IOException {
        String bgOperatorHost = getBgOperatorHost(platformClient);
        String bgCreds = getBGOperatorCredentials(platformClient);
        return getBGOperationStatus(PROTOCOL_PREFIX + bgOperatorHost, bgCreds);
    }

    public static BGContextResponse getBGOperationStatus(String ingressUrl, String creds) throws IOException {
        log.info("getBGOperationStatus");
        Request request = new Request.Builder()
                .url(ingressUrl + "/api/bluegreen/v1/operation/status")
                .addHeader("Authorization", "Basic " + creds)
                .get()
                .build();
        try (Response response = okHttpClient.newCall(request).execute()) {
            assertEquals(200, response.code());
            String jsonResponse = response.body().string();
            log.info("getBGOperationStatus response: {}", jsonResponse);
            BGContextResponse bgContextResponse = new Gson().fromJson(jsonResponse, BGContextResponse.class);
            return bgContextResponse;
        }
    }

    public static String getBGOperatorCredentials(KubernetesClient platformClient) {
        if (StringUtils.isNotBlank(BG_OPERATOR_CREDS)) {
            return BG_OPERATOR_CREDS;
        }
        Secret secret = platformClient.secrets().inNamespace(CONTROLLER_NAMESPACE).withName(BLUEGREEN_CONTROLLER_SECRET_NAME).get();
        byte[] decodedLogin = Base64.getDecoder().decode(secret.getData().get("login"));
        byte[] decodedPassword = Base64.getDecoder().decode(secret.getData().get("password"));
        String creds = new String(decodedLogin) + ":" + new String(decodedPassword);
        byte[] bytesEncoded = Base64.getEncoder().encode(creds.getBytes());
        BG_OPERATOR_CREDS = new String(bytesEncoded);
        return BG_OPERATOR_CREDS;
    }

    public static void testBGAvailableOperations(KubernetesClient platformClient, Operation... operations) throws IOException {
        String bgOperatorHost = getBgOperatorHost(platformClient);
        String bgCreds = getBGOperatorCredentials(platformClient);

        List<String> availableOperations = getBGAvailableOperations(bgOperatorHost, bgCreds);
        List<String> expectedOperations = Arrays.stream(operations).map(Operation::getName).collect(Collectors.toList());
        assertIterableEquals(expectedOperations, availableOperations);
    }

    private static List<String> getBGAvailableOperations(String ingressUrl, String creds) throws IOException {
        log.info("Get BG Available Operations");
        Request request = new Request.Builder()
                .url(PROTOCOL_PREFIX + ingressUrl + "/api/bluegreen/v1/available-actions")
                .addHeader("Authorization", "Basic " + creds)
                .get()
                .build();
        try (Response response = okHttpClient.newCall(request).execute()) {
            assertEquals(200, response.code());
            String jsonResponse = response.body().string();
            log.info("Available Operations : {}", jsonResponse);
            return new Gson().fromJson(jsonResponse, ArrayList.class);
        } catch (Throwable error) {
            log.error(ExceptionUtils.getStackTrace(error));
            log.error("Failed to get available operations with error: " + error.getMessage());
            throw error;
        }
    }

    public static void failOperation(KubernetesClient platformClient, String operation, String creds) throws IOException {
        String bgOperatorHost = getBgOperatorHost(platformClient);

        Request request = new Request.Builder()
                .url(PROTOCOL_PREFIX + bgOperatorHost + "/api/bluegreen/v1/" + operation + "/status")
                .addHeader("Authorization", "Basic " + creds)
                .get()
                .build();
        try (Response response = okHttpClient.newCall(request).execute()) {
            assertTrue(!response.isSuccessful() && response.code() != 401 && response.code() != 403);
        }
    }

    public static void assertWithTimeout(AssertionCheck assertion) {
        assertWithTimeout(assertion, POLLING_DELAY_IN_SECONDS, DEFAULT_WAIT_TIMEOUT_IN_SECONDS);
    }

    public static void assertWithTimeout(AssertionCheck assertion, int pollingDelayInSeconds, int waitTimeoutInSeconds) {
        Instant startTime = Instant.now();
        Throwable lastError = null;
        while ((Duration.between(startTime, Instant.now()).getSeconds()) <= waitTimeoutInSeconds) {
            try {
                assertion.check();
                log.info("Check successful");
                return; // condition met, all ok. just return
            } catch (Throwable error) {
                lastError = error;
                log.info("Check failed, waiting " + pollingDelayInSeconds + " seconds before next attempt");
                waitForSeconds(pollingDelayInSeconds);
            }
        }
        // we waited for duration of specified timeout, fail with timeout
        fail("Timed out waiting for successful check. Last error: " + lastError);
    }

    public static void waitForSeconds(int timeInSeconds) {
        try {
            TimeUnit.SECONDS.sleep(timeInSeconds);
        } catch (InterruptedException e) {
            log.warn("Interrupted during sleep, ignoring", e);
        }
    }

    public static void withTimeOut(Supplier<Boolean> function) {
        Instant updateTime = Instant.now();
        while ((Duration.between(updateTime, Instant.now()).getSeconds()) <= DEFAULT_WAIT_TIMEOUT_IN_SECONDS) {
            if (function.get()) {
                return;
            }
            log.info("Condition failed. Waiting {} seconds before next attempt", POLLING_DELAY_IN_SECONDS);
            waitForSeconds(POLLING_DELAY_IN_SECONDS);
        }
        fail("Timed out waiting for successful conditional");
    }

    public static void waitUntilTasksAreCompleted(KubernetesClient platformClient) {
        log.info("Wait until tasks completed");
        String bgOperatorHost = getBgOperatorHost(platformClient);
        String bgCreds = getBGOperatorCredentials(platformClient);

        int completedCounter = 0;
        Instant updateTime = Instant.now();
        while ((Duration.between(updateTime, Instant.now()).getSeconds()) <= DEFAULT_WAIT_TIMEOUT_IN_SECONDS) {
            BGContextResponse bgContextResponse;
            try {
                bgContextResponse = getBGOperationStatus(PROTOCOL_PREFIX + bgOperatorHost, bgCreds);
            } catch (Throwable error) {
                log.info("Get bg status failed, waiting {} seconds before next attempt. Error: {}", POLLING_DELAY_IN_SECONDS, error.getMessage());
                waitForSeconds(POLLING_DELAY_IN_SECONDS);
                continue;
            }
            assertNotNull(bgContextResponse);

            int currentCompletedSteps = 0;
            for (DetailedState state : bgContextResponse.getDetailedState()) {
                log.info("Step '{}' has status '{}'", state.getStep(), state.getStatus());
                assertNotEquals(FAIL_STATUS, state.getStatus());
                if (state.getStatus().equalsIgnoreCase(COMPLETED_STATUS)) {
                    currentCompletedSteps++;
                }
            }

            if (currentCompletedSteps > completedCounter) {
                completedCounter = currentCompletedSteps;
                updateTime = Instant.now();
            }

            if (completedCounter > 0 && bgContextResponse.getDetailedState().size() == completedCounter) {
                log.info("All tasks completed");
                return;
            }
            log.info("Not all tasks are completed. Waiting {} seconds before next attempt", POLLING_DELAY_IN_SECONDS);
            waitForSeconds(POLLING_DELAY_IN_SECONDS);
        }
        fail("Timed out waiting for successful process");
    }

    public static void testBgMetrics(KubernetesClient platformClient, String expectedMetrics) throws IOException {
        String bgOperatorHost = getBgOperatorHost(platformClient);
        String bgCreds = getBGOperatorCredentials(platformClient);

        String actualMetrics = getBgMetrics(bgOperatorHost, bgCreds);
        assertTrue(checkMetricsMatched(actualMetrics, expectedMetrics));
    }

    private static boolean checkMetricsMatched(String actualMetricsText, String expectedMetricsText) {
        String[] expectedMetrics = expectedMetricsText.split("\n");

        for (String expectedMetric : expectedMetrics) {
            if (!actualMetricsText.contains(expectedMetric)) {
                log.warn("Expected metric not found: " + expectedMetric);
                return false;
            }
        }
        log.info("Metrics matched");
        return true;
    }

    private static String getBgMetrics(String ingressUrl, String creds) throws IOException {
        log.info("Get BG Metrics");
        Request request = new Request.Builder()
                .url(PROTOCOL_PREFIX + ingressUrl + BG_GET_METRICS_URL)
                .addHeader("Authorization", "Basic " + creds)
                .get()
                .build();
        try (Response response = okHttpClient.newCall(request).execute()) {
            assertEquals(200, response.code());
            String responseText = response.body().string();
            log.info("Available BG Metrics : {}", responseText);
            return responseText;
        } catch (Throwable error) {
            log.error(ExceptionUtils.getStackTrace(error));
            log.error("Failed to get BG metrics with error: " + error.getMessage());
            throw error;
        }
    }

    private static String getBgOperatorHost(KubernetesClient platformClient) {
        if (StringUtils.isNotBlank(BG_OPERATOR_INGRESS_HOST)) {
            return BG_OPERATOR_INGRESS_HOST;
        }
        List<Ingress> ingresses = platformClient.network().v1().ingresses().list().getItems();
        Optional<Ingress> bgOperatorIngress = ingresses.stream().filter(ingress -> ingress.getMetadata().getName().equals(BG_OPERATOR_INGRESS_NAME)).findFirst();
        BG_OPERATOR_INGRESS_HOST = bgOperatorIngress.get().getSpec().getRules().get(0).getHost();
        return BG_OPERATOR_INGRESS_HOST;
    }

    public static BgLock getBGLock(KubernetesClient platformClient) throws IOException {
        log.info("Get bg lock.");
        Request request = new Request.Builder()
                .url(PROTOCOL_PREFIX + getBgOperatorHost(platformClient) + "/api/bluegreen/v1/lock")
                .addHeader("Authorization", "Basic " + getBGOperatorCredentials(platformClient))
                .get()
                .build();
        try (Response response = okHttpClient.newCall(request).execute()) {
            assertEquals(200, response.code(), "Expected 200 status code");
            String jsonResponse = response.body().string();
            log.info("Bg lock response: '{}'", jsonResponse);
            return new Gson().fromJson(jsonResponse, BgLock.class);
        }
    }

    public static BGContextResponse runOperation(KubernetesClient platformClient, Operation operation) throws IOException {
        return runOperation(platformClient, operation, Collections.emptySet());
    }

    public static BGContextResponse runOperation(KubernetesClient platformClient, Operation operation, Set<String> tasksToSkip) throws IOException {
        log.info("Run operation '{}'. Skip tasks: {}", operation.getName(), tasksToSkip);
        String bgOperatorHost = getBgOperatorHost(platformClient);
        String bgOperatorCreds = getBGOperatorCredentials(platformClient);
        String toJson = objectMapperIndent.writeValueAsString(new OperationRequest(tasksToSkip));
        RequestBody body = RequestBody.create(toJson, JSON);
        Request request = new Request.Builder()
                .url(PROTOCOL_PREFIX + bgOperatorHost + "/api/bluegreen/v1/operation/" + operation)
                .addHeader("Authorization", "Basic " + bgOperatorCreds)
                .post(body)
                .build();
        try (Response response = okHttpClient.newCall(request).execute()) {
            assertTrue(response.code() == 200 || response.code() == 202, "Expected 200 or 202 status code");
            String jsonResponse = response.body().string();
            return new Gson().fromJson(jsonResponse, BGContextResponse.class);
        }
    }

    private static ObjectMapper initObjectMapperIndent() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        objectMapper.configure(SerializationFeature.INDENT_OUTPUT, true);
        return objectMapper;
    }

    public static void scaleDeployment(KubernetesClient platformClient, String namespace, String deploymentName, int countOfReplicas) {
        log.info("scaleDeployment {} on {}, countOfReplicas: {}", deploymentName, namespace, countOfReplicas);
        DeploymentList deploymentList = platformClient.apps().deployments().inNamespace(namespace).list();
        log.info("deploymentList: {}", deploymentList);
        Deployment deployment = deploymentList.getItems().stream().filter(item -> item.getMetadata().getName().equals(deploymentName))
                .findFirst().orElse(null);
        log.info("deployment: {}", deployment);
        if (deployment != null) {
            deployment.getSpec().setReplicas(countOfReplicas);
            platformClient.resource(deployment).inNamespace(namespace).update();
        } else {
            log.warn("Deployment with name {} not found in namespace {}", deploymentName, namespace);
        }
    }

    public static int getCountOfReplicas(KubernetesClient platformClient, String namespace, String deploymentName) {
        DeploymentList deploymentList = platformClient.apps().deployments().inNamespace(namespace).list();
        Deployment deployment = deploymentList.getItems().stream().filter(item -> item.getMetadata().getName().equals(deploymentName))
                .findFirst().orElse(null);
        if (deployment != null) {
            return deployment.getSpec().getReplicas();
        } else {
            throw new IllegalArgumentException(String.format("Deployment with name %s not found in namespace %s", deploymentName, namespace));
        }
    }

    public static void testBgPlugin(URL bluegreenPluginUrl, String expectedLastOperation) throws IOException {
        BGPluginHistoryRecord record = getLastBGPluginHistoryRecord(bluegreenPluginUrl);
        log.info("Last BG Plugin History Record: {}", record);
        assertNotNull(record);
        assertEquals(expectedLastOperation, record.getOperation());
        assertEquals(EXPECTED_CLOUD_VALUE, record.getCloudId().getCloudName());
        assertEquals(EXPECTED_TENANT_VALUE, record.getCloudId().getTenant());
        assertEquals(EXPECTED_USERNAME_VALUE, record.getRequestedBy().getUsername());
        assertEquals(EXPECTED_EMAIL_VALUE, record.getRequestedBy().getEmail());
    }

    private static BGPluginHistoryRecord getLastBGPluginHistoryRecord(URL bluegreenPluginUrl) throws IOException {
        BGPluginHistoryResponse historyRecords = getPluginHistory(bluegreenPluginUrl);
        return historyRecords.stream().max((o1, o2) -> o1.getBGState().getUpdateTime().compareTo(o2.getBGState().getUpdateTime()))
                .orElse(null);
    }

    @SuppressWarnings("unchecked")
    private static BGPluginHistoryResponse getPluginHistory(URL bluegreenPluginUrl) throws IOException {
        log.info("Get BG Plugin History");
        Request request = new Request.Builder()
                .url(bluegreenPluginUrl + BG_PLUGIN_EXAMPLE_HISTORY)
                .get()
                .build();

        try (Response response = okHttpClient.newCall(request).execute()) {
            assertEquals(200, response.code());
            String jsonResponse = response.body().string();
            log.info("BG Plugin History : {}", jsonResponse);
            return new Gson().fromJson(jsonResponse, BGPluginHistoryResponse.class);
        } catch (Throwable error) {
            log.error(ExceptionUtils.getStackTrace(error));
            log.error("Failed to get BG Plugin History with error: " + error.getMessage());
            throw error;
        }
    }

    @FunctionalInterface
    public interface AssertionCheck {
        void check() throws Throwable;
    }
}
