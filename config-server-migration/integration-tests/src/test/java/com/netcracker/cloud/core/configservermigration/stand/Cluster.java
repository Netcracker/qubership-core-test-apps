package com.netcracker.cloud.core.configservermigration.stand;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.NonDeletingOperation;

/**
 * The names both phases address on the stand, the check that core-legacy-api stands behind the Service
 * {@code config-server}, and the ConfigMap that carries the baseline from one phase to the other.
 *
 * <p>The baseline lives in the cluster because the phases run as separate Maven runs.
 */
public final class Cluster {

    public static final String CORE_NAMESPACE = "core";
    public static final String CONSUL_NAMESPACE = "consul";
    public static final String CONFIG_SERVER = "config-server";
    public static final String CONSUL_SERVER = "consul-consul-server";

    private static final String CORE_LEGACY_API = "core-legacy-api";
    private static final String BASELINE_CONFIG_MAP = "config-server-migration-baseline";
    private static final String BASELINE_KEY = "baseline.json";

    private Cluster() {
    }

    /**
     * Throws unless config-server is uninstalled and core-legacy-api is ready: with config-server still behind the
     * Service, the second phase would compare config-server with itself.
     */
    public static void requireCoreLegacyApiInPlaceOfConfigServer(KubernetesClient kubernetes) {
        if (deployment(kubernetes, CONFIG_SERVER) != null) {
            throw new IllegalStateException("deployment " + CONFIG_SERVER + " is still installed in namespace "
                    + CORE_NAMESPACE + ": the replacement step did not uninstall it");
        }
        Deployment coreLegacyApi = deployment(kubernetes, CORE_LEGACY_API);
        if (coreLegacyApi == null) {
            throw new IllegalStateException("deployment " + CORE_LEGACY_API + " is not installed in namespace "
                    + CORE_NAMESPACE);
        }
        Integer ready = coreLegacyApi.getStatus() == null ? null : coreLegacyApi.getStatus().getReadyReplicas();
        if (ready == null || ready < 1) {
            throw new IllegalStateException("deployment " + CORE_LEGACY_API + " has no ready replica");
        }
    }

    public static void saveBaseline(KubernetesClient kubernetes, String json) {
        ConfigMap configMap = new ConfigMapBuilder()
                .withNewMetadata()
                .withName(BASELINE_CONFIG_MAP)
                .withNamespace(CORE_NAMESPACE)
                .endMetadata()
                .addToData(BASELINE_KEY, json)
                .build();
        kubernetes.configMaps().inNamespace(CORE_NAMESPACE).resource(configMap)
                .createOr(NonDeletingOperation::update);
    }

    public static void deleteBaseline(KubernetesClient kubernetes) {
        kubernetes.configMaps().inNamespace(CORE_NAMESPACE).withName(BASELINE_CONFIG_MAP).delete();
    }

    public static String loadBaseline(KubernetesClient kubernetes) {
        ConfigMap configMap = kubernetes.configMaps().inNamespace(CORE_NAMESPACE).withName(BASELINE_CONFIG_MAP).get();
        if (configMap == null || configMap.getData() == null || configMap.getData().get(BASELINE_KEY) == null) {
            throw new IllegalStateException("no " + BASELINE_KEY + " in ConfigMap " + BASELINE_CONFIG_MAP
                    + " in namespace " + CORE_NAMESPACE + ": the before-migration phase did not record it");
        }
        return configMap.getData().get(BASELINE_KEY);
    }

    private static Deployment deployment(KubernetesClient kubernetes, String name) {
        return kubernetes.apps().deployments().inNamespace(CORE_NAMESPACE).withName(name).get();
    }
}
