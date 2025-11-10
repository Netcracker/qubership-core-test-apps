package com.netcracker.it.spring;

import com.netcracker.cloud.junit.cloudcore.extension.annotations.Cloud;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.EnableExtension;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.PortForward;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.Value;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.networking.v1beta1.Ingress;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.ServiceResource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URL;
import java.util.List;
import java.util.Optional;

import static com.netcracker.it.spring.CommonOperations.*;
import static com.netcracker.it.spring.Const.*;
import static com.netcracker.it.spring.ResourceOperations.createBluegreenPlugin;
import static com.netcracker.it.spring.ResourceOperations.getBluegreenPlugin;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnableExtension
@Slf4j
@Tag("bg-e2e-phase:after-deploy-candidate[baseline]")
@Disabled
public class CandidateIT {

    @PortForward(serviceName = @Value(PUBLIC_GW_SERVICE_NAME), cloud = @Cloud(namespace = @Value(prop = ORIGIN_NAMESPACE_ENV_NAME)))
    private static URL publicGWServerUrlOrigin;

    @PortForward(serviceName = @Value(PRIVATE_GW_SERVICE_NAME), cloud = @Cloud(namespace = @Value(prop = PEER_NAMESPACE_ENV_NAME)))
    private static URL privateGWServerUrlPeer;

    @Cloud(namespace = @Value(prop = ORIGIN_NAMESPACE_ENV_NAME))
    private static KubernetesClient platformClientOrigin;

    @Cloud(namespace = @Value(prop = PEER_NAMESPACE_ENV_NAME))
    private static KubernetesClient platformClientPeer;

    @BeforeAll
    public static void init() throws Exception {
        assertNotNull(publicGWServerUrlOrigin);
        assertNotNull(privateGWServerUrlPeer);
        assertNotNull(platformClientOrigin);
        assertNotNull(platformClientPeer);
    }

    @Test
    public void testCheckResourcesAndPerformRequests() throws IOException {
        //Check that ingress and service for test-ingress-gw2 are created on Edge Router
        ServiceResource<Service> service = platformClientOrigin.services().inNamespace(CONTROLLER_NAMESPACE).withName(INGRESS_2_GW_SERVICE_NAME);
        assertNotNull(service.get());
        List<Ingress> ingressList = platformClientOrigin.network().ingresses().inNamespace(CONTROLLER_NAMESPACE).list().getItems();
        Optional<Ingress> testIngress2 = ingressList.stream().filter(ingress -> ingress.getMetadata().getName().equals(INGRESS_2_GW_INGRESS_NAME_IN_CONTROLLER)).findFirst();
        assertTrue(testIngress2.isPresent());

        String testIngress2Host = testIngress2.get().getSpec().getRules().get(0).getHost();
        Optional<Ingress> testIngress = ingressList.stream().filter(ingress -> ingress.getMetadata().getName().equals(INGRESS_GW_INGRESS_NAME_IN_CONTROLLER)).findFirst();
        assertTrue(testIngress.isPresent());
        String testIngressHost = testIngress.get().getSpec().getRules().get(0).getHost();

        // Perform tests on test-ingress-gw2 with header
        testIngressWithHeader(PROTOCOL_PREFIX + testIngress2Host, 200, PEER_NAMESPACE, "v2", X_VERSION_NAME_VALUE_CANDIDATE);

        // Check that the same tests without header return 404 (Active doesn't contain test-ingress-gw2)
        testIngressWithoutHeader(PROTOCOL_PREFIX + testIngress2Host, 404, "");

        // Check primary login
        testIngressWithoutHeader(PROTOCOL_PREFIX + testIngressHost, 200, ORIGIN_NAMESPACE);

        // Check secondary login
        testIngressWithHeader(PROTOCOL_PREFIX + testIngressHost, 200, PEER_NAMESPACE, "v2", X_VERSION_NAME_VALUE_CANDIDATE);

        if (!USE_DIRECT_TOKEN.equals("true")) {
            // Check that token from Active is valid on Candidate
            testIngressWithHeader(PROTOCOL_PREFIX + testIngressHost, 200, PEER_NAMESPACE, "v2", X_VERSION_NAME_VALUE_CANDIDATE);

            // Check that token from Candidate is valid on Active
            testIngressWithoutHeader(PROTOCOL_PREFIX + testIngressHost, 200, ORIGIN_NAMESPACE);
        }
    }

    @Test
    public void testScaleUpPodsOnActiveNamespace() throws Exception {
        try {
            String active = getActiveNamespace(platformClientOrigin);
            scaleDeployment(platformClientOrigin, active, MESH_TEST_SERVICE_GO_V1, HPA_TEST_COUNT_OF_REPLICAS);
        } catch (Exception e) {
            log.error(ExceptionUtils.getStackTrace(e));
            throw e;
        }

    }

    @Test
    public void testCreateBgPluginIfNotExist() throws Exception {
        try {
            GenericKubernetesResource bgPlugin = getBluegreenPlugin(platformClientOrigin);
            if (bgPlugin == null) {
                log.info("BG Plugin not found. Create new one.");
                createBluegreenPlugin(platformClientOrigin);
            }
        } catch (Exception e) {
            log.error(ExceptionUtils.getStackTrace(e));
            throw e;
        }
    }
}
