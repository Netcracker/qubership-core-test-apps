package com.netcracker.it.meshtestservicespring;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.Cloud;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.EnableExtension;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.PortForward;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.Value;
import com.netcracker.cloud.junit.cloudcore.extension.service.PortForwardService;
import com.netcracker.it.meshtestservicespring.model.MediationRoute;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.apps.DeploymentList;

import io.fabric8.kubernetes.api.model.networking.v1beta1.*;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static com.netcracker.it.meshtestservicespring.CommonOperations.*;
import static com.netcracker.it.meshtestservicespring.Const.*;
import static com.netcracker.it.meshtestservicespring.ResourceOperations.*;
import static com.netcracker.it.meshtestservicespring.model.Metrics.METRICS_BEFORE_WARMUP;
import static com.netcracker.it.meshtestservicespring.model.Operation.WARMUP;
import static org.apache.commons.lang3.StringUtils.isNotEmpty;
import static org.junit.jupiter.api.Assertions.*;

@EnableExtension
@Tag("bg-e2e-phase:after-apps-deployment[baseline]")
@Slf4j
public class BeforeWarmupIT {

    protected static final ObjectMapper objectMapper = initObjectMapper();
    private static final MediaType JSON = MediaType.parse("application/json");

    @PortForward(serviceName = @Value(INTERNAL_GW_SERVICE_NAME), cloud = @Cloud(namespace = @Value(prop = ORIGIN_NAMESPACE_ENV_NAME)))
    private static URL internalGWServerUrl;

    @PortForward(serviceName = @Value(PAAS_MEDIATION_SERVICE_NAME), cloud = @Cloud(namespace = @Value(prop = ORIGIN_NAMESPACE_ENV_NAME)))
    private static URL paasMediationService;

    @PortForward(serviceName = @Value(PUBLIC_GW_SERVICE_NAME))
    private static URL publicGWServerUrl;

    @Cloud(namespace = @Value(prop = ORIGIN_NAMESPACE_ENV_NAME))
    private static KubernetesClient platformClient;

    @Cloud
    private static PortForwardService portForwardService;

    private static String namespace;
    private static String ingressName;
    private static String ingressHost;
    private static String m2mToken;

    @BeforeAll
    public static void init() throws Exception {
        assertNotNull(internalGWServerUrl);
        assertNotNull(platformClient);
        namespace = platformClient.getNamespace();
        ingressName = INGRESS_GW_INGRESS_NAME + "-from-paas-mediation";
        ingressHost = ingressName + "-" + namespace + "." + ENV_CLOUD_PUBLIC_HOST;
    }

    @Test
    void testNodePorts() throws Exception {
        validateNodePorts(platformClient, ORIGIN_NAMESPACE, internalGWServerUrl);
    }

    @Test
    public void testCreateTestEntities() {
        createStatefulSet(platformClient, ORIGIN_NAMESPACE, "test-stateful-set", 1);
        createDaemonSet(platformClient, ORIGIN_NAMESPACE, "test-daemon-set");
    }

    @Test
    @Disabled
    public void testCheckResourcesAndPerformRequests() throws Exception {
        validatePeerNamespace();
        validateServices();
        validateRoutes();
        testBGAvailableOperations(platformClient, WARMUP);
    }

    @Test
    @Disabled
    public void testMetrics() throws Exception {
        testBgMetrics(platformClient, METRICS_BEFORE_WARMUP);
    }

    @Test
    @Disabled
    public void testCreateHPA() throws Exception {
        try {
            String activeNamespace = getActiveNamespace(platformClient);
            if (isNotEmpty(activeNamespace)) {
                removeHorizontalPodAutoscaler(platformClient, activeNamespace, MESH_TEST_SERVICE_GO_HPA);
                createHorizontalPodAutoscaler(platformClient, activeNamespace, MESH_TEST_SERVICE_GO_HPA, MESH_TEST_SERVICE_GO_V1,
                        1, HPA_TEST_COUNT_OF_REPLICAS_FOR_ROLLBACK, 1800);
            }
        } catch (Exception e) {
            log.error(ExceptionUtils.getStackTrace(e));
            throw e;
        }
    }

    @Test
    @Disabled
    public void testCreateBgPlugin() throws Exception {
        try {
            createBluegreenPlugin(platformClient);
        } catch (Exception e) {
            log.error(ExceptionUtils.getStackTrace(e));
            throw e;
        }
    }

    private void validateServices() {
        final List<Service> controllerServices = platformClient.services().inNamespace(CONTROLLER_NAMESPACE).list().getItems();
        log.info("Controller services: {}", controllerServices.stream().map(service -> service.getMetadata().getName()).collect(Collectors.toList()));

        // Exclude bg services from list
        final List<Service> replicatedControllerServices = controllerServices.stream()
                .filter(service -> !CONTROLLER_SERVICES_NAMES.contains(service.getMetadata().getName())).collect(Collectors.toList());
        log.info("Replicated controller services: {}", replicatedControllerServices.stream().map(service -> service.getMetadata().getName()).collect(Collectors.toList()));

        final List<Service> originServices = filterServices(ORIGIN_NAMESPACE);
        log.info("Origin services: {}", originServices.stream().map(service -> service.getMetadata().getName()).collect(Collectors.toList()));

        //Check that controller namespace has replicated services
        assertEquals(replicatedControllerServices.size(), originServices.size(), "Services quantity in controller and origin namespaces should be equals.");
        hasEntity("Service", replicatedControllerServices, originServices);
    }

    private void validatePeerNamespace() {
        // Check that ns-2 is empty (ingresses, services, deployments, configMaps, secrets*, serviceAccounts, roles-, roleBindings-, gateways)
        List<Ingress> peerIngresses = platformClient.network().ingresses().inNamespace(PEER_NAMESPACE).list().getItems();
        log.info("Peer ingresses: {}", peerIngresses.stream().map(service -> service.getMetadata().getName()).collect(Collectors.toList()));
        assertTrue(peerIngresses.isEmpty(), "Before warmup peer namespace must be without any ingresses.");

        final List<Service> peerServices = filterServices(PEER_NAMESPACE);
        log.info("Peer services: {}", peerServices.stream().map(service -> service.getMetadata().getName()).collect(Collectors.toList()));
        assertTrue(peerServices.isEmpty(), "Before warmup peer namespace must be without any services.");

        DeploymentList deploymentList2 = platformClient.apps().deployments().inNamespace(PEER_NAMESPACE).list();
        assertEquals(0, deploymentList2.getItems().size(), "Before warmup peer namespace must be without any deployments.");

        SecretList secretList2 = platformClient.secrets().inNamespace(PEER_NAMESPACE).list();
        assertFalse(secretList2.getItems().isEmpty(), "Before warmup peer namespace should have at least default secret");
        assertTrue(secretList2.getItems().size() <= 2, () -> {
            List<String> secretNames = secretList2.getItems().stream().map(s -> s.getMetadata().getName()).collect(Collectors.toList());
            return String.format("%s. Found secrets: %s", "Before warmup peer namespace can have only default secret and satellite-trusted-key", secretNames);
        });
        ServiceAccountList serviceAccountList2 = platformClient.serviceAccounts().inNamespace(PEER_NAMESPACE).list();
        assertEquals(1, serviceAccountList2.getItems().size(), "Before warmup peer namespace should have only default service account.");

        CustomResourceDefinitionContext gatewayCRDContext = new CustomResourceDefinitionContext.Builder()
                .withName("gateways.core.qubership.org")
                .withGroup("core.qubership.org")
                .withScope("Namespaced")
                .withVersion("v1")
                .withPlural("gateways")
                .build();
        GenericKubernetesResourceList gatewaysList = platformClient.genericKubernetesResources(gatewayCRDContext).inNamespace(PEER_NAMESPACE).list();
        assertEquals(0, gatewaysList.getItems().size());
    }

    private void validateRoutes() throws Exception {
        //Check that controller namespace has replicated ingresses
        List<Ingress> controllerIngresses = platformClient.network().ingresses().inNamespace(CONTROLLER_NAMESPACE).list().getItems();
        log.info("Controller ingresses: {}", controllerIngresses.stream().map(service -> service.getMetadata().getName()).collect(Collectors.toList()));

        List<Ingress> replicatedControllerIngresses = controllerIngresses.stream()
                .filter(ingress -> !ingress.getMetadata().getName().equals(BG_OPERATOR_INGRESS_NAME)).collect(Collectors.toList());
        log.info("Replicated controller ingresses: {}", replicatedControllerIngresses.stream().map(service -> service.getMetadata().getName()).collect(Collectors.toList()));

        List<Ingress> originIngresses = platformClient.network().ingresses().inNamespace(ORIGIN_NAMESPACE).list().getItems();
        log.info("Origin ingresses: {}", originIngresses.stream().map(service -> service.getMetadata().getName()).collect(Collectors.toList()));

        //Check that controller namespace has replicated ingresses
        assertEquals(replicatedControllerIngresses.size(), originIngresses.size(), "Ingresses quantity in controller and origin namespaces should be equals.");
        hasEntity("Ingress", replicatedControllerIngresses, originIngresses);

        // Perform regular tests on V1 using controller ingresses instead ns-1 ingresses from Gateway chart
        Optional<Ingress> foundIngressFromFacade = controllerIngresses.stream().filter(ingress -> ingress.getMetadata().getName().equals(INGRESS_GW_INGRESS_NAME_IN_CONTROLLER)).findFirst();
        assertTrue(foundIngressFromFacade.isPresent());
        String foundIngressHostFromFacade = foundIngressFromFacade.get().getSpec().getRules().get(0).getHost();
        testIngressWithoutHeader(PROTOCOL_PREFIX + foundIngressHostFromFacade, 200, ORIGIN_NAMESPACE);

        // Perform regular tests via ingress from ingres chart (on controller)
        Optional<Ingress> foundIngressFromIngress = controllerIngresses.stream().filter(ingress -> ingress.getMetadata().getName().equals(INGRESS_GW_INGRESS_NAME_FROM_INGRESS_IN_CONTROLLER)).findFirst();
        assertTrue(foundIngressFromIngress.isPresent());
        String foundIngressHostFromIngress = foundIngressFromIngress.get().getSpec().getRules().get(0).getHost();
        testIngressWithoutHeader(PROTOCOL_PREFIX + foundIngressHostFromIngress, 200, ORIGIN_NAMESPACE);
    }

    @Test
    public void testCreateIngressViaPaasMediation() throws IOException {
        // Create ingress via paas-mediation
        Ingress testIngress = createTestIngress();
        MediationRoute mediationRoute = new MediationRoute(testIngress);
        String toJson = objectMapper.writeValueAsString(mediationRoute);
        RequestBody body = RequestBody.create(toJson, JSON);
        Request request = new Request.Builder()
                .url(paasMediationService + "api/v2/namespaces/" + namespace + "/routes")
                .put(body)
                .build();
        try (Response response = okHttpClient.newCall(request).execute()) {
            assertTrue(response.code() == 200 || response.code() == 201, String.format("Current paas response code: '%d'", response.code()));
            String jsonResponse = response.body().string();
            log.info("Response: {}, body {}", response, jsonResponse);
        }
    }

    private List<Service> filterServices(String namespace) {
        ServiceList serviceList = platformClient.services().inNamespace(namespace).list();
        return serviceList.getItems();
    }

    public <T extends HasMetadata> void hasEntity(String type, List<T> firstEntityList, List<T> secondEntityList) {
        for (T metadata : firstEntityList) {
            String name = metadata.getMetadata().getName().replace("-" + ORIGIN_NAMESPACE, "");
            assertTrue(secondEntityList.stream().anyMatch(service -> service.getMetadata().getName().equals(name)), type + " does not exist:" + name);
        }
    }

    private static ObjectMapper initObjectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        objectMapper.configure(SerializationFeature.INDENT_OUTPUT, true);
        return objectMapper;
    }

    private Ingress createTestIngress() {
        ObjectMeta objectMeta = new ObjectMeta();
        objectMeta.setName(ingressName);
        objectMeta.setNamespace(namespace);
        objectMeta.setLabels(Collections.singletonMap("it", "edge-router-controller"));

        IngressRule rule = new IngressRule();
        rule.setHost(ingressHost);

        HTTPIngressPath path = new HTTPIngressPath();
        path.setPath("/");
        path.setPathType("Prefix");

        IngressBackend backend = new IngressBackend();
        backend.setServiceName(INGRESS_GW_SERVICE_NAME);
        backend.setServicePort(new IntOrString("web"));

        path.setBackend(backend);

        rule.setHttp(new HTTPIngressRuleValue());
        rule.getHttp().getPaths().add(path);

        IngressSpec spec = new IngressSpec();
        spec.getRules().add(rule);

        Ingress ingress = new Ingress();
        ingress.setMetadata(objectMeta);
        ingress.setSpec(spec);

        return ingress;
    }
}
