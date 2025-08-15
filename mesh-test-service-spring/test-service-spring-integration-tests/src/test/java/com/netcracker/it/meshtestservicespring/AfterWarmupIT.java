package com.netcracker.it.meshtestservicespring;

//import com.netcracker.cloud.junit.cloudcore.extension.annotations.Namespace;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.*;
//import com.netcracker.cloud.junit.cloudcore.extension.client.PlatformClient;
import com.netcracker.cloud.junit.cloudcore.extension.service.Endpoint;
import com.netcracker.cloud.junit.cloudcore.extension.service.NetSocketAddress;
import com.netcracker.cloud.junit.cloudcore.extension.service.PortForwardParams;
import com.netcracker.cloud.junit.cloudcore.extension.service.PortForwardService;
//import com.netcracker.cloud.junit.cloudcore.service.ITHelper;
import com.netcracker.it.meshtestservicespring.model.BGContext;
import com.netcracker.it.meshtestservicespring.model.BGContextResponse;
import com.netcracker.it.meshtestservicespring.model.Plugin;
import com.netcracker.it.meshtestservicespring.utils.ClosablePortForward;
import com.netcracker.it.meshtestservicespring.utils.Utils;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.apps.*;
import io.fabric8.kubernetes.api.model.networking.v1beta1.Ingress;
import io.fabric8.kubernetes.api.model.networking.v1beta1.IngressList;
import io.fabric8.kubernetes.api.model.rbac.Role;
import io.fabric8.kubernetes.api.model.rbac.RoleBinding;
import io.fabric8.kubernetes.api.model.rbac.RoleBindingList;
import io.fabric8.kubernetes.api.model.rbac.RoleList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
//import io.fabric8.openshift.api.model.Route;
//import io.fabric8.openshift.api.model.RouteList;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URL;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static com.netcracker.it.meshtestservicespring.CommonOperations.*;
import static com.netcracker.it.meshtestservicespring.Const.*;
import static com.netcracker.it.meshtestservicespring.model.Metrics.METRICS_AFTER_WARMUP;
import static com.netcracker.it.meshtestservicespring.model.Operation.COMMIT;
import static com.netcracker.it.meshtestservicespring.model.Operation.PROMOTE;
import static org.junit.jupiter.api.Assertions.*;

@EnableExtension
@Slf4j
public class AfterWarmupIT {
    enum KIND {
        INGRESS,
        SERVICE,
        DEPLOYMENT,
        STATEFULSET,
        DAEMONSET,
        CONFIG_MAP,
        SECRET,
        SERVICE_ACCOUNT,
        ROLE,
        ROLE_BINDING,
        CUSTOM_RESOURCE
    }

//    @Named(PUBLIC_GW_SERVICE_NAME)
//    @Scheme("http")
//    @PortForward
//    @Namespace(property = CONTROLLER_NAMESPACE_ENV_NAME)
    @PortForward(serviceName = @Value(PUBLIC_GW_SERVICE_NAME), cloud = @Cloud(namespace = @Value(prop = CONTROLLER_NAMESPACE_ENV_NAME)))
    private static URL publicGWServerUrlController;

//    @Named(PRIVATE_GW_SERVICE_NAME)
//    @Scheme("http")
//    @PortForward
//    @Namespace(property = ORIGIN_NAMESPACE_ENV_NAME)
    @PortForward(serviceName = @Value(PRIVATE_GW_SERVICE_NAME), cloud = @Cloud(namespace = @Value(prop = ORIGIN_NAMESPACE_ENV_NAME)))
    private static URL privateGWServerUrlOrigin;

    //    @Client
//    @Namespace(property = ORIGIN_NAMESPACE_ENV_NAME)
    @Cloud(namespace = @Value(prop = ORIGIN_NAMESPACE_ENV_NAME))
    protected static KubernetesClient platformClientOrigin;

//    @PortForwardClient
    @Cloud
    private static PortForwardService portForwardService;

    //    private static ITHelper itHelperOrigin;
    private static String tokenOrigin;

//    @Named(INTERNAL_GW_SERVICE_NAME)
//    @Scheme("http")
//    @PortForward
//    @Namespace(property = PEER_NAMESPACE_ENV_NAME)
    @PortForward(serviceName = @Value(INTERNAL_GW_SERVICE_NAME), cloud = @Cloud(namespace = @Value(prop = PEER_NAMESPACE_ENV_NAME)))
    private static URL internalGWServerUrlPeer;

    //    @Client
//    @Namespace(property = PEER_NAMESPACE_ENV_NAME)
//    private static PlatformClient platformClientPeer;
    @Cloud(namespace = @Value(prop = PEER_NAMESPACE_ENV_NAME))
    private static KubernetesClient platformClientPeer;

//    private static ITHelper itHelperPeer;

    //    private static String tokenPeer;
    private static BGContext bgContext;

    private static String currentActiveNamespace = "";

    @BeforeAll
    public static void init() throws Exception {
        assertNotNull(privateGWServerUrlOrigin);
        assertNotNull(platformClientOrigin);
//        itHelperOrigin = new ITHelper(privateGWServerUrlOrigin, platformClientOrigin);
//        tokenOrigin = itHelperOrigin.getTokenService().loginAsCloudAdmin();
        assertNotNull(internalGWServerUrlPeer);
        assertNotNull(platformClientPeer);
//        itHelperPeer = new ITHelper(internalGWServerUrlPeer, platformClientPeer);
//        tokenPeer = itHelperPeer.getTokenService().getTokenBuilder().asUser().asCloudAdmin().reLogin().login();
        bgContext = getBGContext(platformClientOrigin);
    }

    @Test
    @Tag("bg-e2e-phase:after-warmup-#1[baseline]")
    public void testNodePorts1() throws Exception {
        validateNodePorts(platformClientOrigin, ORIGIN_NAMESPACE, privateGWServerUrlOrigin);
    }

    @Test
    @Tag("bg-e2e-phase:after-warmup-#2[baseline]")
    public void testNodePorts2() throws Exception {
        validateNodePorts(platformClientOrigin, PEER_NAMESPACE, privateGWServerUrlOrigin);
    }

    @Test
    @Tag("bg-e2e-phase:after-warmup-#1[baseline]")
    public void testCheckResourcesAndPerformRequestsWarmup1() throws Exception {
        testCheckResourcesAndPerformRequests(ORIGIN_NAMESPACE, PEER_NAMESPACE, "v1", "v2");
    }

    @Test
    @Tag("bg-e2e-phase:after-warmup-#2[baseline]")
    public void testCheckResourcesAndPerformRequestsWarmup2() throws Exception {
        testCheckResourcesAndPerformRequests(PEER_NAMESPACE, ORIGIN_NAMESPACE, "v2", "v3");
    }

    @Test
    @Tag("bg-e2e-phase:after-warmup-#1[baseline]")
    @Tag("bg-e2e-phase:after-warmup-#2[baseline]")
    @Disabled
    public void testBGAvailableOperations1And2() throws IOException {
        testBGAvailableOperations(platformClientOrigin, PROMOTE, COMMIT);
    }

    @Test
    @Tag("bg-e2e-phase:after-warmup-#1[baseline]")
    @Tag("bg-e2e-phase:after-warmup-#2[baseline]")
    @Disabled
    public void testBgMetrics1And2() throws IOException {
        testBgMetrics(platformClientOrigin, METRICS_AFTER_WARMUP);
    }

    @Test
    @Tag("bg-e2e-phase:after-retry-warmup[baseline]")
    @Disabled
    public void testCheckResourcesAndPerformRequestsRetryWarmup4() throws Exception {
        //After retry terminate
        testCheckResourcesAndPerformRequests(ORIGIN_NAMESPACE, PEER_NAMESPACE, "v3", "v5");
    }

    public void testCheckResourcesAndPerformRequests(String active, String candidate, String activeXVersion, String candidateXVersion) throws Exception {
        testCheckResources(active, candidate, true);

        // Perform the same tests on ns-2 but with header. Check that response contains namespace name ns-2
        List<Ingress> ingressListController = platformClientOrigin.network().ingresses().inNamespace(CONTROLLER_NAMESPACE).list().getItems();
        Optional<Ingress> testGatewayIngress = ingressListController.stream().filter(ingress -> ingress.getMetadata().getName().equals(INGRESS_GW_INGRESS_NAME_IN_CONTROLLER)).findFirst();
        String testGatewayHost = testGatewayIngress.get().getSpec().getRules().get(0).getHost();

        testIngressWithoutHeader(PROTOCOL_PREFIX + testGatewayHost, 200, active);

        testIngressWithHeader(PROTOCOL_PREFIX + testGatewayHost, 200, active, activeXVersion, X_VERSION_NAME_VALUE_ACTIVE);

        assertWithTimeout(() -> testIngressWithHeader(PROTOCOL_PREFIX + testGatewayHost, 200, candidate, candidateXVersion, X_VERSION_NAME_VALUE_CANDIDATE));

        testContextPropagation(active, candidate, activeXVersion, candidateXVersion);

        // Do not make port forward through annotations on candidate namespace because scaleup task may be skip during warmup.
        try (ClosablePortForward portForward = new ClosablePortForward(portForwardService,
                PEER_NAMESPACE, SERVICE_NAME, 8080)) {
            Request request = new Request.Builder()
                    .url(portForward.getUrl() + "/api/v1/mesh-test-service-spring/hello")
                    .get()
                    .build();
            try (Response response = okHttpClient.newCall(request).execute()) {
                assertEquals(200, response.code());
            }
        }
    }

    public static void testCheckResources(String active, String candidate, boolean isAppsScaled) {
        testCheckResources(active, candidate, isAppsScaled, platformClientOrigin);
    }

    public static void testCheckResources(String active, String candidate, boolean isAppsScaled, KubernetesClient client) {
        //Wait until warmup is done
        waitUntilTasksAreCompleted(client);
        currentActiveNamespace = active;

        //Check that ns-2 contains exactly the same entities that ns-1 does (ingresses, services, deployments, configMaps, secrets, serviceAccounts, roles!!!!, roleBindings!!!!, gateways).
        ListOptions options = new ListOptions();
        List<Ingress> ingressListN1 = client.network().ingresses().inNamespace(active).list().getItems();
        List<Ingress> ingressListN2 = client.network().ingresses().inNamespace(candidate).list().getItems();
        KubernetesResourceList<Ingress> kubernetesResourceIngressList1 = new IngressList();// RouteList();
        KubernetesResourceList<Ingress> kubernetesResourceIngressList2 = new IngressList();
        kubernetesResourceIngressList1.getItems().addAll(ingressListN1);
        kubernetesResourceIngressList2.getItems().addAll(ingressListN2);

        List<Ingress> kubernetesResourceIngressList1Filtered = kubernetesResourceIngressList1.getItems().stream().filter(resource -> !resource.getMetadata().getAnnotations().containsKey("helm.sh/hook")).collect(Collectors.toList());
        kubernetesResourceIngressList1.getItems().clear();
        kubernetesResourceIngressList1.getItems().addAll(kubernetesResourceIngressList1Filtered);

        List<Ingress> kubernetesResourceIngressList2Filtered = kubernetesResourceIngressList2.getItems().stream().filter(resource -> !resource.getMetadata().getAnnotations().containsKey("helm.sh/hook")).collect(Collectors.toList());
        kubernetesResourceIngressList2.getItems().clear();
        kubernetesResourceIngressList2.getItems().addAll(kubernetesResourceIngressList2Filtered);

        hasResources(kubernetesResourceIngressList2, kubernetesResourceIngressList1, KIND.INGRESS);
        assertEquals(
                kubernetesResourceIngressList1.getItems().size(),
                kubernetesResourceIngressList2.getItems().size(),
                () -> String.format("The number of ingresses is not equal.\nIngresses on active: %s.\nIngresses on candidate: %s.",
                        objectToStringWithSneakyThrow(kubernetesResourceIngressList1),
                        objectToStringWithSneakyThrow(kubernetesResourceIngressList2)
                )
        );

        ServiceList serviceListN1 = client.services().inNamespace(active).list();
        List<Service> serviceListN1Filtered = serviceListN1.getItems().stream().filter(resource -> !resource.getMetadata().getAnnotations().containsKey("helm.sh/hook")).collect(Collectors.toList());
        serviceListN1.setItems(serviceListN1Filtered);
        ServiceList serviceListN2 = client.services().inNamespace(candidate).list();
        List<Service> serviceListN2Filtered = serviceListN2.getItems().stream().filter(resource -> !resource.getMetadata().getAnnotations().containsKey("helm.sh/hook")).collect(Collectors.toList());
        serviceListN2.setItems(serviceListN2Filtered);
        //candidate.size() + 1 - Additional LoadBalancer service is created in the active namespace

        hasResources(serviceListN2, serviceListN1, KIND.SERVICE);
        assertEquals(
                serviceListN1.getItems().size(),
                serviceListN2.getItems().size() + 1,
                () -> String.format("The number of services is not equal.\nServices on active: %s.\nServices on candidate: %s.",
                        objectToStringWithSneakyThrow(serviceListN1),
                        objectToStringWithSneakyThrow(serviceListN2)
                )
        );

        DeploymentList deploymentListN1 = client.apps().deployments().inNamespace(active).list();
        List<Deployment> deploymentListN1Filtered = deploymentListN1.getItems().stream().filter(resource -> !resource.getMetadata().getAnnotations().containsKey("helm.sh/hook")).collect(Collectors.toList());
        deploymentListN1.setItems(deploymentListN1Filtered);
        DeploymentList deploymentListN2 = client.apps().deployments().inNamespace(candidate).list();
        List<Deployment> deploymentListN2Filtered = deploymentListN2.getItems().stream().filter(resource -> !resource.getMetadata().getAnnotations().containsKey("helm.sh/hook")).collect(Collectors.toList());
        deploymentListN2.setItems(deploymentListN2Filtered);

        hasResources(deploymentListN2, deploymentListN1, KIND.DEPLOYMENT);
        assertEquals(
                deploymentListN1.getItems().size(),
                deploymentListN2.getItems().size(),
                () -> String.format("The number of deployments is not equal.\nDeployments on active: %s.\nDeployments on candidate: %s.",
                        objectToStringWithSneakyThrow(deploymentListN1),
                        objectToStringWithSneakyThrow(deploymentListN2)
                )
        );

        ConfigMapList configMapListN1 = client.configMaps().inNamespace(active).list();
        List<ConfigMap> configMapListN1Filtered = configMapListN1.getItems().stream().filter(resource -> !resource.getMetadata().getAnnotations().containsKey("helm.sh/hook")).collect(Collectors.toList());
        configMapListN1.setItems(configMapListN1Filtered);
        ConfigMapList configMapListN2 = client.configMaps().inNamespace(candidate).list();
        List<ConfigMap> configMapListN2Filtered = configMapListN2.getItems().stream().filter(resource -> !resource.getMetadata().getAnnotations().containsKey("helm.sh/hook")).collect(Collectors.toList());
        configMapListN2.setItems(configMapListN2Filtered);

        hasResources(configMapListN2, configMapListN1, KIND.CONFIG_MAP);
        assertEquals(
                configMapListN1.getItems().size(),
                configMapListN2.getItems().size(),
                () -> String.format("The number of configmaps is not equal.\nConfigmaps on active: %s.\nConfigmaps on candidate: %s.",
                        objectToStringWithSneakyThrow(configMapListN1),
                        objectToStringWithSneakyThrow(configMapListN2)
                )
        );

        SecretList secretListN1 = client.secrets().inNamespace(active).list();
        List<Secret> secretListN1Filtered = secretListN1.getItems().stream().filter(resource -> !resource.getMetadata().getAnnotations().containsKey("helm.sh/hook")).collect(Collectors.toList());
        secretListN1.setItems(secretListN1Filtered);
        SecretList secretListN2 = client.secrets().inNamespace(candidate).list();
        List<Secret> secretListN2Filtered = secretListN2.getItems().stream().filter(resource -> !resource.getMetadata().getAnnotations().containsKey("helm.sh/hook")).collect(Collectors.toList());
        secretListN1.setItems(secretListN2Filtered);

        hasResources(secretListN2, secretListN1, KIND.SECRET);
        assertEquals(
                secretListN1.getItems().size(),
                secretListN2.getItems().size(),
                () -> String.format("The number of secrets is not equal.\nSecrets on active: %s.\nSecrets on candidate: %s.",
                        objectToStringWithSneakyThrow(secretListN1),
                        objectToStringWithSneakyThrow(secretListN2)
                )
        );

        ServiceAccountList serviceAccountListN1 = client.serviceAccounts().inNamespace(active).list();
        List<ServiceAccount> serviceAccountListN1Filtered = serviceAccountListN1.getItems().stream().filter(resource -> !resource.getMetadata().getAnnotations().containsKey("helm.sh/hook")).collect(Collectors.toList());
        serviceAccountListN1.setItems(serviceAccountListN1Filtered);
        ServiceAccountList serviceAccountListN2 = client.serviceAccounts().inNamespace(candidate).list();
        List<ServiceAccount> serviceAccountListN2Filtered = serviceAccountListN2.getItems().stream().filter(resource -> !resource.getMetadata().getAnnotations().containsKey("helm.sh/hook")).collect(Collectors.toList());
        serviceAccountListN2.setItems(serviceAccountListN2Filtered);

        hasResources(serviceAccountListN2, serviceAccountListN1, KIND.SERVICE_ACCOUNT);
        assertEquals(
                serviceAccountListN1.getItems().size(),
                serviceAccountListN2.getItems().size(),
                () -> String.format("The number of SA is not equal.\nSA on active: %s.\nSA on candidate: %s.",
                        objectToStringWithSneakyThrow(serviceAccountListN1),
                        objectToStringWithSneakyThrow(serviceAccountListN2)
                )
        );

        RoleList roleListN1 = client.rbac().roles().inNamespace(active).list();
        List<Role> roleListN1Filtered = roleListN1.getItems().stream().filter(resource -> !resource.getMetadata().getAnnotations().containsKey("helm.sh/hook")).collect(Collectors.toList());
        roleListN1.setItems(roleListN1Filtered);
        RoleList roleListN2 = client.rbac().roles().inNamespace(candidate).list();
        List<Role> roleListN2Filtered = roleListN2.getItems().stream().filter(resource -> !resource.getMetadata().getAnnotations().containsKey("helm.sh/hook")).collect(Collectors.toList());
        roleListN2.setItems(roleListN2Filtered);

        hasResources(roleListN2, roleListN1, KIND.ROLE);
        assertEquals(
                roleListN1.getItems().size(),
                roleListN2.getItems().size(),
                () -> String.format("The number of roles is not equal.\nRoles on active: %s.\nRoles on candidate: %s.",
                        objectToStringWithSneakyThrow(roleListN1),
                        objectToStringWithSneakyThrow(roleListN2)
                )
        );

        RoleBindingList roleBindingListN1 = client.rbac().roleBindings().inNamespace(active).list();
        List<RoleBinding> roleBindingListN1Filtered = roleBindingListN1.getItems().stream().filter(resource -> !resource.getMetadata().getAnnotations().containsKey("helm.sh/hook")).collect(Collectors.toList());
        roleBindingListN1.setItems(roleBindingListN1Filtered);
        RoleBindingList roleBindingListN2 = client.rbac().roleBindings().inNamespace(candidate).list();
        List<RoleBinding> roleBindingListN2Filtered = roleBindingListN2.getItems().stream().filter(resource -> !resource.getMetadata().getAnnotations().containsKey("helm.sh/hook")).collect(Collectors.toList());
        roleBindingListN2.setItems(roleBindingListN2Filtered);

        hasResources(roleBindingListN2, roleBindingListN1, KIND.ROLE_BINDING);
        assertEquals(
                roleBindingListN1.getItems().size(),
                roleBindingListN2.getItems().size(),
                () -> String.format("The number of RB is not equal.\nRB on active: %s.\nRB on candidate: %s.",
                        objectToStringWithSneakyThrow(roleBindingListN1),
                        objectToStringWithSneakyThrow(roleBindingListN2)
                )
        );

        CustomResourceDefinitionContext gatewayCRDContext = new CustomResourceDefinitionContext.Builder()
                .withName("gateways.core.qubership.org")
                .withGroup("core.qubership.org")
                .withScope("Namespaced")
                .withVersion("v1")
                .withPlural("gateways")
                .build();
        GenericKubernetesResourceList gatewaysListN1 = client.genericKubernetesResources(gatewayCRDContext).inNamespace(active).list();
        List<GenericKubernetesResource> gatewaysListN1Filtered = gatewaysListN1.getItems().stream().filter(resource -> !resource.getMetadata().getAnnotations().containsKey("helm.sh/hook")).collect(Collectors.toList());
        gatewaysListN1.setItems(gatewaysListN1Filtered);
        GenericKubernetesResourceList gatewaysListN2 = client.genericKubernetesResources(gatewayCRDContext).inNamespace(candidate).list();
        List<GenericKubernetesResource> gatewaysListN2Filtered = gatewaysListN2.getItems().stream().filter(resource -> !resource.getMetadata().getAnnotations().containsKey("helm.sh/hook")).collect(Collectors.toList());
        gatewaysListN2.setItems(gatewaysListN2Filtered);

        hasResources(gatewaysListN1, gatewaysListN2, KIND.CUSTOM_RESOURCE);
        assertEquals(
                gatewaysListN1.getItems().size(),
                gatewaysListN2.getItems().size(),
                () -> String.format("The number of gateways CR is not equal.\ngateways on active: %s.\ngateways on candidate: %s.",
                        objectToStringWithSneakyThrow(gatewaysListN1),
                        objectToStringWithSneakyThrow(gatewaysListN2)
                )
        );

        StatefulSetList statefulSetListN1 = client.apps().statefulSets().inNamespace(active).list();
        List<StatefulSet> statefulSetListN1Filtered = statefulSetListN1.getItems().stream().filter(resource -> !resource.getMetadata().getAnnotations().containsKey("helm.sh/hook")).collect(Collectors.toList());
        statefulSetListN1.setItems(statefulSetListN1Filtered);
        StatefulSetList statefulSetListN2 = client.apps().statefulSets().inNamespace(candidate).list();
        List<StatefulSet> statefulSetListN2Filtered = statefulSetListN2.getItems().stream().filter(resource -> !resource.getMetadata().getAnnotations().containsKey("helm.sh/hook")).collect(Collectors.toList());
        statefulSetListN2.setItems(statefulSetListN2Filtered);

        hasResources(gatewaysListN1, gatewaysListN2, KIND.CUSTOM_RESOURCE);
        assertEquals(
                statefulSetListN1.getItems().size(),
                statefulSetListN2.getItems().size(),
                () -> String.format("The number of stateful sets is not equal.\nStateful sets on active: %s.\nStateful sets on candidate: %s.",
                        objectToStringWithSneakyThrow(statefulSetListN1),
                        objectToStringWithSneakyThrow(statefulSetListN2)
                )
        );

        DaemonSetList daemonSetListN1 = client.apps().daemonSets().inNamespace(active).list();
        List<DaemonSet> daemonSetListN1Filtered = daemonSetListN1.getItems().stream().filter(resource -> !resource.getMetadata().getAnnotations().containsKey("helm.sh/hook")).collect(Collectors.toList());
        daemonSetListN1.setItems(daemonSetListN1Filtered);
        DaemonSetList daemonSetListN2 = client.apps().daemonSets().inNamespace(candidate).list();
        List<DaemonSet> daemonSetListN2Filtered = daemonSetListN2.getItems().stream().filter(resource -> !resource.getMetadata().getAnnotations().containsKey("helm.sh/hook")).collect(Collectors.toList());
        daemonSetListN2.setItems(daemonSetListN2Filtered);

        hasResources(daemonSetListN2, daemonSetListN1, KIND.DAEMONSET);
        assertEquals(
                daemonSetListN1.getItems().size(),
                daemonSetListN2.getItems().size(),
                () -> String.format("The number of daemon sets is not equal.\nDaemon sets on active: %s.\nDaemon sets on candidate: %s.",
                        objectToStringWithSneakyThrow(daemonSetListN1),
                        objectToStringWithSneakyThrow(daemonSetListN2)
                )
        );

        PodList activePods = client.pods().inNamespace(active).list();
        List<Pod> activePodsFiltered = activePods.getItems().stream().filter(resource -> !resource.getMetadata().getAnnotations().containsKey("helm.sh/hook")).collect(Collectors.toList());
        activePods.setItems(activePodsFiltered);
        PodList candidatePods = client.pods().inNamespace(candidate).list();
        List<Pod> candidatePodsFiltered = candidatePods.getItems().stream().filter(resource -> !resource.getMetadata().getAnnotations().containsKey("helm.sh/hook")).collect(Collectors.toList());
        candidatePods.setItems(candidatePodsFiltered);
        assertNotNull(activePods);
        assertNotNull(candidatePods);
        if (isAppsScaled) {
            assertTrue(Utils.isNotEmpty(activePods.getItems()));
            assertTrue(Utils.isNotEmpty(candidatePods.getItems()));
        } else {
            assertTrue(Utils.isNotEmpty(activePods.getItems()));
            assertTrue(Utils.isNotEmpty(candidatePods.getItems()));
            // 1 DaemonSet
            assertEquals(1, candidatePods.getItems().size(), "Expect only 1 daemon set on candidate");
        }
    }

    private void testContextPropagation(String active, String candidate, String activeXVersion, String candidateXVersion) throws Exception {
        testServiceThroughPrivateGateway(SPRING_TO_QUARKUS_URL, active, candidate, activeXVersion, candidateXVersion, QUARKUS_SERVICE_NAME);
        testServiceThroughPrivateGateway(GO_TO_SPRING_URL, active, candidate, activeXVersion, candidateXVersion, SPRING_SERVICE_NAME);
        testServiceThroughPrivateGateway(QUARKUS_TO_GO_URL, active, candidate, activeXVersion, candidateXVersion, GO_SERVICE_NAME);
    }

    private void testServiceThroughPrivateGateway(String url, String active, String candidate, String activeXVersion, String candidateXVersion, String expectedService) throws Exception {
        testRequestWithoutHeader(publicGWServerUrlController + url, 200, active, expectedService);
        testRequestWithHeader(publicGWServerUrlController + url, 200, active, activeXVersion, expectedService, X_VERSION_NAME_VALUE_ACTIVE);
        testRequestWithHeader(publicGWServerUrlController + url, 200, candidate, candidateXVersion, expectedService, X_VERSION_NAME_VALUE_CANDIDATE);
    }

    @Test
    @Disabled
    public void testGetBGOperationStatus() throws IOException {
        String secret = getBGOperatorCredentials(platformClientOrigin);
        Request request = new Request.Builder()
                .url("http://bluegreen-controller-cloud-rnd-bg-controller/api/bluegreen/v1/operation/status")
                .addHeader("Authorization", "Basic " + secret)
                .get()
                .build();
        try (Response response = okHttpClient.newCall(request).execute()) {
            assertEquals(200, response.code());
            String jsonResponse = response.body().string();
            System.out.println(jsonResponse);
        }
    }

    public static <T extends KubernetesResourceList<?>> void hasResources(T kubernetesResourceList, T kubernetesResourceList2, KIND kind) {
        for (KubernetesResource kubernetesResource : kubernetesResourceList2.getItems()) {
            hasResource(kubernetesResourceList, kubernetesResource, kind);
        }
    }

    public static <T extends KubernetesResourceList<?>> void hasResource(T kubernetesResourceList, KubernetesResource kubernetesResource, KIND kind) {
        switch (kind) {
            case INGRESS:
                Ingress ingress = (Ingress) kubernetesResource;
                assertTrue(kubernetesResourceList.getItems().stream().anyMatch(kubernetesResourceFromList -> {
                    Ingress ingressFromList = (Ingress) kubernetesResourceFromList;
                    return ingressFromList.getMetadata().getName().equals(ingress.getMetadata().getName());
                }), "Ingress does not exist:" + ingress.getMetadata().getName());
                return;
            case SERVICE:
                Service service = (Service) kubernetesResource;
                if (service.getSpec().getType().equals(SERVICE_TYPE_LOAD_BALANCER)) {
                    // LoadBalancer service is created only in active namespace
                    assertEquals(currentActiveNamespace, service.getMetadata().getNamespace());
                    assertTrue(kubernetesResourceList.getItems().stream().noneMatch(kubernetesResourceFromList -> {
                        Service serviceFromList = (Service) kubernetesResourceFromList;
                        return serviceFromList.getMetadata().getName().equals(service.getMetadata().getName());
                    }), "Load balancing service must not exist on the candidate:" + service.getMetadata().getName());
                    return;
                }
                assertTrue(kubernetesResourceList.getItems().stream().anyMatch(kubernetesResourceFromList -> {
                    Service serviceFromList = (Service) kubernetesResourceFromList;
                    return serviceFromList.getMetadata().getName().equals(service.getMetadata().getName());
                }), "Service does not exist:" + service.getMetadata().getName());
                return;
            case DEPLOYMENT:
                Deployment deployment = (Deployment) kubernetesResource;
                assertTrue(kubernetesResourceList.getItems().stream().anyMatch(kubernetesResourceFromList -> {
                    Deployment deploymentFromList = (Deployment) kubernetesResourceFromList;
                    return deploymentFromList.getMetadata().getName().equals(deployment.getMetadata().getName());
                }), "Deployment does not exist:" + deployment.getMetadata().getName());
                return;
            case STATEFULSET:
                StatefulSet statefulSet = (StatefulSet) kubernetesResource;
                assertTrue(kubernetesResourceList.getItems().stream().anyMatch(kubernetesResourceFromList -> {
                    StatefulSet statefulSetList = (StatefulSet) kubernetesResourceFromList;
                    return statefulSetList.getMetadata().getName().equals(statefulSet.getMetadata().getName());
                }), "StatefulSet does not exist:" + statefulSet.getMetadata().getName());
                return;
            case DAEMONSET:
                DaemonSet daemonSet = (DaemonSet) kubernetesResource;
                assertTrue(kubernetesResourceList.getItems().stream().anyMatch(kubernetesResourceFromList -> {
                    DaemonSet daemonSetFromList = (DaemonSet) kubernetesResourceFromList;
                    return daemonSetFromList.getMetadata().getName().equals(daemonSet.getMetadata().getName());
                }), "DaemonSet does not exist:" + daemonSet.getMetadata().getName());
                return;
            case CONFIG_MAP:
                ConfigMap configMap = (ConfigMap) kubernetesResource;
                assertTrue(kubernetesResourceList.getItems().stream().anyMatch(kubernetesResourceFromList -> {
                    ConfigMap configMapFromList = (ConfigMap) kubernetesResourceFromList;
                    return configMapFromList.getMetadata().getName().equals(configMap.getMetadata().getName());
                }), "Configmap does not exist:" + configMap.getMetadata().getName());
                return;
            case SECRET:
                Secret secret = (Secret) kubernetesResource;
                assertTrue(kubernetesResourceList.getItems().stream().anyMatch(kubernetesResourceFromList -> {
                    Secret secretFromList = (Secret) kubernetesResourceFromList;
                    return secretFromList.getMetadata().getName().equals(secret.getMetadata().getName());
                }), "Secret does not exist:" + secret.getMetadata().getName());
                return;
            case SERVICE_ACCOUNT:
                ServiceAccount serviceAccount = (ServiceAccount) kubernetesResource;
                assertTrue(kubernetesResourceList.getItems().stream().anyMatch(kubernetesResourceFromList -> {
                    ServiceAccount serviceAccountFromList = (ServiceAccount) kubernetesResourceFromList;
                    return serviceAccountFromList.getMetadata().getName().equals(serviceAccount.getMetadata().getName());
                }), "ServiceAccount does not exist:" + serviceAccount.getMetadata().getName());
                return;
            case ROLE:
                Role role = (Role) kubernetesResource;
                assertTrue(kubernetesResourceList.getItems().stream().anyMatch(kubernetesResourceFromList -> {
                    Role roleFromList = (Role) kubernetesResourceFromList;
                    return roleFromList.getMetadata().getName().equals(role.getMetadata().getName());
                }), "Role does not exist:" + role.getMetadata().getName());
                return;
            case ROLE_BINDING:
                RoleBinding roleBinding = (RoleBinding) kubernetesResource;
                final String targetRBName = getTargetRBName(roleBinding);
                assertTrue(kubernetesResourceList.getItems().stream().anyMatch(kubernetesResourceFromList -> {
                    RoleBinding roleBindingFromList = (RoleBinding) kubernetesResourceFromList;
                    return roleBindingFromList.getMetadata().getName().equals(targetRBName) ||
                            roleBindingFromList.getMetadata().getName().replaceAll(ORIGIN_NAMESPACE, PEER_NAMESPACE).equals(targetRBName);
                }), "RoleBinding does not exist:" + targetRBName);
                return;
            case CUSTOM_RESOURCE:
                GenericKubernetesResource genericKubernetesResource = (GenericKubernetesResource) kubernetesResource;
                assertTrue(kubernetesResourceList.getItems().stream().anyMatch(kubernetesResourceFromList -> {
                    GenericKubernetesResource genericKubernetesResourceFromList = (GenericKubernetesResource) kubernetesResourceFromList;
                    return genericKubernetesResourceFromList.getMetadata().getName().equals(genericKubernetesResource.getMetadata().getName());
                }), "GenericKubernetesResource does not exist:" + genericKubernetesResource.getMetadata().getName());
                return;
            default:
                fail("Kind " + kind + " does not exist");
        }
    }

    private static String getTargetRBName(RoleBinding roleBinding) {
        String name = roleBinding.getMetadata().getName();
        if (name.endsWith("-" + ORIGIN_NAMESPACE)) {
            String prefix = name.substring(0, name.lastIndexOf("-" + ORIGIN_NAMESPACE) + 1);
            return prefix + PEER_NAMESPACE;
        } else if (name.endsWith("-" + PEER_NAMESPACE)) {
            String prefix = name.substring(0, name.lastIndexOf("-" + PEER_NAMESPACE) + 1);
            return prefix + ORIGIN_NAMESPACE;
        }

        return name;
    }

    @SneakyThrows
    private static String objectToStringWithSneakyThrow(Object o) {
        return objectMapperIndent.writeValueAsString(o);
    }

    @Test
    @Tag("bg-e2e-phase:after-warmup-#1[baseline]")
    @Disabled
    public void testBgPlugin1() throws Exception {
//        URL bluegreenPluginUrl = portForwardService.createPortForward(BG_PLUGIN_NAMESPACE, BG_PLUGIN_SERVICE_NAME, "http", 8080);
        try (ClosablePortForward portForward = new ClosablePortForward(portForwardService,
                             BG_PLUGIN_NAMESPACE, BG_PLUGIN_SERVICE_NAME, 8080)) {
            CommonOperations.testBgPlugin(portForward.getUrl(), "warmup");
        }
    }

    @Test
    @Tag("bg-e2e-phase:after-warmup-#2[baseline]")
    @Disabled
    public void testBgPlugin2() throws IOException {
        BGContextResponse bgContextResponse = CommonOperations.getBGOperationStatus(platformClientOrigin);
        List<Plugin> plugins = bgContextResponse.getBGContext().getPlugins();
        assertTrue(plugins == null || plugins.isEmpty());
    }
}
