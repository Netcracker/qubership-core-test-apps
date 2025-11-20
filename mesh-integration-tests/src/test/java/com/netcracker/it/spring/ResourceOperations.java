package com.netcracker.it.spring;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.netcracker.it.spring.utils.KeyValuePair;
import com.netcracker.it.spring.utils.Utils;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceBuilder;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.apps.DaemonSet;
import io.fabric8.kubernetes.api.model.apps.DaemonSetStatus;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.fabric8.kubernetes.api.model.apps.StatefulSetStatus;
import io.fabric8.kubernetes.api.model.autoscaling.v2.HorizontalPodAutoscaler;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.Resource;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.Map;

import static com.netcracker.it.spring.CommonOperations.getEntityFromYamlFile;
import static com.netcracker.it.spring.CommonOperations.withTimeOut;
import static com.netcracker.it.spring.Const.BG_PLUGIN_NAMESPACE;
import static com.netcracker.it.spring.Const.CONTROLLER_NAMESPACE;
import static com.netcracker.it.spring.utils.Utils.newMap;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Slf4j
public class ResourceOperations {
    protected static final ObjectMapper objectMapper = initObjectMapper();

    private static ObjectMapper initObjectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return objectMapper;
    }

    public static void createDaemonSet(KubernetesClient platformClient, String namespace, String name) {
        DaemonSet daemonSetTemplate = getEntityFromYamlFile("daemon_set.yaml",
                Map.of(
                        "name", name,
                        "label", "ds_" + name,
                        "image", getPublicGatewayImage(platformClient, namespace),
                        "node", getPublicGatewayNode(platformClient, namespace)
                ),
                DaemonSet.class
        );

        log.info("Create DaemonSet: {}", daemonSetTemplate);
        platformClient.apps().daemonSets().inNamespace(namespace).resource(daemonSetTemplate).createOrReplace();

        withTimeOut(() -> {
            log.info("Wait until StatefulSet ready");
            DaemonSet foundDaemonSet = platformClient.apps().daemonSets().inNamespace(namespace).withName(name).get();
            if (foundDaemonSet == null) {
                log.warn("DaemonSet not found");
                return false;
            }
            log.info("Found DaemonSet: {}", foundDaemonSet.getMetadata().getName());

            DaemonSetStatus status = foundDaemonSet.getStatus();
            if (status == null) {
                log.warn("Status not found");
                return false;
            }

            log.info("Status: {}", status);
            return status.getNumberReady() == 1 && status.getCurrentNumberScheduled() == 1;
        });
    }

    public static void createStatefulSet(KubernetesClient platformClient, String namespace, String name, int replicas) {
        StatefulSet statefulSetTemplate = getEntityFromYamlFile("stateful_set.yaml",
                Map.of(
                        "name", name,
                        "label", "ss_" + name,
                        "image", getPublicGatewayImage(platformClient, namespace)
                ),
                StatefulSet.class
        );

        log.info("Create StatefulSet: {}", statefulSetTemplate);
        platformClient.apps().statefulSets().inNamespace(namespace).resource(statefulSetTemplate).createOrReplace();

        withTimeOut(() -> {
            log.info("Wait until StatefulSet ready");
            StatefulSet foundStatefulSet = platformClient.apps().statefulSets().inNamespace(namespace).withName(name).get();
            if (foundStatefulSet == null) {
                log.warn("StatefulSet not found");
                return false;
            }
            log.info("Found StatefulSet: {}", foundStatefulSet.getMetadata().getName());

            StatefulSetStatus status = foundStatefulSet.getStatus();
            if (status == null) {
                log.warn("Status not found");
                return false;
            }

            log.info("Status: {}", status);
            return status.getReplicas() == replicas && status.getAvailableReplicas() == replicas;
        });
    }

    private static String getPublicGatewayImage(KubernetesClient platformClient, String namespace) {
        Deployment publicGateway = platformClient.apps().deployments().inNamespace(namespace).withName("public-frontend-gateway").get();
        assertNotNull(publicGateway, "Can not find public gateway deployment");

        return publicGateway.getSpec().getTemplate().getSpec().getContainers().get(0).getImage();
    }

    private static String getPublicGatewayNode(KubernetesClient platformClient, String namespace) {
        PodList podList = platformClient.pods().inNamespace(namespace).withLabel("name", "public-frontend-gateway").list();
        assertNotNull(podList, "Can not find public-frontend-gateway pods");
        assertTrue(Utils.isNotEmpty(podList.getItems()), "Can not find public-frontend-gateway pods");

        return podList.getItems().get(0).getSpec().getNodeName();
    }

    public static void createHorizontalPodAutoscaler(KubernetesClient platformClient, String namespace, String name, String deploymentName,
                                                     int minReplicas, int maxReplicas, int scaleDownPeriod) {
        GenericKubernetesResource hpa = buildHorizontalPodAutoscaler(namespace, name, deploymentName, minReplicas, maxReplicas, scaleDownPeriod);
        log.info("createHorizontalPodAutoscaler: {}", hpa);
        platformClient.genericKubernetesResources(hpa.getApiVersion(), hpa.getKind())
                .inNamespace(namespace)
                .resource(hpa)
                .create();
    }

    public static void removeHorizontalPodAutoscaler(KubernetesClient platformClient, String namespace, String hpaName) {
        Resource<HorizontalPodAutoscaler> hpa = platformClient.autoscaling().v2().horizontalPodAutoscalers().inNamespace(namespace).withName(hpaName);
        log.info("removeHorizontalPodAutoscaler: {}", hpa);
        if (hpa != null) {
            hpa.delete();
        } else {
            log.warn("HPA with name {} not found in namespace {}", hpaName, namespace);
        }
    }

    private static GenericKubernetesResource buildHorizontalPodAutoscaler(final String namespace, final String name, final String deploymentName,
                                                                          final int minReplicas, final int maxReplicas, final int scaleDownPeriod) {
        return new GenericKubernetesResourceBuilder()
                .withKind("HorizontalPodAutoscaler")
                .withApiVersion("autoscaling/v2")
                .withMetadata(new ObjectMetaBuilder()
                        .withName(name)
                        .withNamespace(namespace)
                        .withLabels(newMap(new KeyValuePair<>("name", name)))
                        .build())
                .addToAdditionalProperties("spec", newMap(
                        new KeyValuePair<>("scaleTargetRef", newMap(
                                new KeyValuePair<>("apiVersion", "apps/v1"),
                                new KeyValuePair<>("kind", "Deployment"),
                                new KeyValuePair<>("name", deploymentName))),
                        new KeyValuePair<>("minReplicas", minReplicas),
                        new KeyValuePair<>("maxReplicas", maxReplicas),
                        new KeyValuePair<>("behavior", newMap(
                                new KeyValuePair<>("scaleDown", newMap(
                                        new KeyValuePair<>("policies", Collections.singletonList(newMap(
                                                new KeyValuePair<>("periodSeconds", scaleDownPeriod),
                                                new KeyValuePair<>("type", "Pods"),
                                                new KeyValuePair<>("value", 1)))),
                                        new KeyValuePair<>("stabilizationWindowSeconds", 1800))),
                                new KeyValuePair<>("scaleUp", newMap(
                                        new KeyValuePair<>("policies", Collections.singletonList(newMap(
                                                new KeyValuePair<>("periodSeconds", 10),
                                                new KeyValuePair<>("type", "Pods"),
                                                new KeyValuePair<>("value", 1)))),
                                        new KeyValuePair<>("stabilizationWindowSeconds", 10))))),
                        new KeyValuePair<>("metrics", Collections.singletonList(newMap(
                                new KeyValuePair<>("resource", newMap(
                                        new KeyValuePair<>("name", "cpu"),
                                        new KeyValuePair<>("target", newMap(
                                                new KeyValuePair<>("averageUtilization", 50),
                                                new KeyValuePair<>("type", "Utilization"))))),
                                new KeyValuePair<>("type", "Resource")))
                        )
                ))
                .build();
    }

    public static GenericKubernetesResource getBluegreenPlugin(KubernetesClient platformClient) {
        GenericKubernetesResource bgPlugin = getEntityFromYamlFile("bg_plugin.yml",
                Map.of("namespace", BG_PLUGIN_NAMESPACE, "controller-namespace", CONTROLLER_NAMESPACE), GenericKubernetesResource.class);
        return platformClient.genericKubernetesResources(bgPlugin.getApiVersion(), bgPlugin.getKind())
                .inNamespace(CONTROLLER_NAMESPACE)
                .resource(bgPlugin)
                .get();
    }

    public static void createBluegreenPlugin(KubernetesClient platformClient) {
        GenericKubernetesResource bgPlugin = getEntityFromYamlFile("bg_plugin.yml",
                Map.of("namespace", BG_PLUGIN_NAMESPACE, "controller-namespace", CONTROLLER_NAMESPACE), GenericKubernetesResource.class);

        log.info("Create BG Plugin: {}", bgPlugin);
        platformClient.genericKubernetesResources(bgPlugin.getApiVersion(), bgPlugin.getKind())
                .inNamespace(CONTROLLER_NAMESPACE)
                .resource(bgPlugin)
                .create();

        withTimeOut(() -> {
            log.info("Wait until BG Plugin ready");
            GenericKubernetesResource foundPlugin = platformClient.genericKubernetesResources(bgPlugin.getApiVersion(), bgPlugin.getKind())
                    .inNamespace(CONTROLLER_NAMESPACE)
                    .resource(bgPlugin).get();

            if (foundPlugin == null) {
                log.warn("BG Plugin not found");
                return false;
            }
            log.info("Found BG Plugin: {}", foundPlugin.getMetadata().getName());

            String status = foundPlugin.get("status", "phase");
            if (status == null) {
                log.warn("BG Plugin status not found");
                return false;
            }

            log.info("Status: {}", status);
            return "Updated".equals(status);
        });
    }

    public static void removeBluegreenPlugin(KubernetesClient platformClient) {
        GenericKubernetesResource bgPlugin = getEntityFromYamlFile("bg_plugin.yml",
                Map.of("namespace", BG_PLUGIN_NAMESPACE, "controller-namespace", CONTROLLER_NAMESPACE), GenericKubernetesResource.class);
        Resource<GenericKubernetesResource> plugin = platformClient.genericKubernetesResources(bgPlugin.getApiVersion(), bgPlugin.getKind())
                .inNamespace(CONTROLLER_NAMESPACE)
                .resource(bgPlugin);
        log.info("removeBluegreenPlugin: {}", plugin);
        if (plugin != null) {
            plugin.delete();
        } else {
            log.warn("BG Plugin not found in namespace {}", CONTROLLER_NAMESPACE);
        }
    }
}
