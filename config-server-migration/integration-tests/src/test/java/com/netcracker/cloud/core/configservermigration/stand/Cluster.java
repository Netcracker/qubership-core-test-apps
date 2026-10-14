package com.netcracker.cloud.core.configservermigration.stand;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.LocalPortForward;
import io.fabric8.kubernetes.client.dsl.NonDeletingOperation;

import java.io.IOException;

/**
 * The Cloud Core stand both phases run against: a client, tunnels to the Consul API and to the Service
 * {@code config-server}, and the ConfigMap that carries the baseline from one phase to the other.
 *
 * <p>The baseline lives in the cluster because the phases run as separate Maven builds, each starting with
 * {@code mvn clean}.
 */
public final class Cluster {

    public static final String CORE_NAMESPACE = "core";

    private static final String CONSUL_NAMESPACE = "consul";
    private static final String CONSUL_SERVER_POD_PREFIX = "consul-consul-server";
    private static final String CONFIG_SERVER = "config-server";
    private static final String CORE_LEGACY_API = "core-legacy-api";
    private static final String BASELINE_CONFIG_MAP = "config-server-migration-baseline";
    private static final String BASELINE_KEY = "baseline.json";

    private Cluster() {
    }

    /**
     * The kubeconfig of the run, with certificate checks relaxed: a kind cluster answers on an address its own
     * certificate does not name.
     */
    public static KubernetesClient newClient() {
        Config config = Config.autoConfigure(null);
        config.setTrustCerts(true);
        config.setDisableHostnameVerification(true);
        String master = System.getProperty("kubernetes.master");
        if (master != null && !master.isBlank()) {
            config.setMasterUrl(master);
        }
        return new KubernetesClientBuilder().withConfig(config).build();
    }

    public static LocalPortForward forwardConsulPort(KubernetesClient kubernetes) {
        Pod server = kubernetes.pods().inNamespace(CONSUL_NAMESPACE).list().getItems().stream()
                .filter(pod -> pod.getMetadata().getName().startsWith(CONSUL_SERVER_POD_PREFIX))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "no pod with prefix " + CONSUL_SERVER_POD_PREFIX + " in namespace " + CONSUL_NAMESPACE));
        return kubernetes.pods()
                .inNamespace(CONSUL_NAMESPACE)
                .withName(server.getMetadata().getName())
                .portForward(8500);
    }

    /** Opens a tunnel to the Service {@code config-server}, whichever of the two services stands behind it. */
    public static LocalPortForward forwardConfigServerPort(KubernetesClient kubernetes) {
        return kubernetes.services().inNamespace(CORE_NAMESPACE).withName(CONFIG_SERVER).portForward(8080);
    }

    public static String url(LocalPortForward portForward) {
        return "http://localhost:" + portForward.getLocalPort();
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

    /** Closes what a phase opened, in an order that survives failures. */
    public static void tearDown(KubernetesClient kubernetes, LocalPortForward... portForwards) {
        for (LocalPortForward portForward : portForwards) {
            if (portForward == null) {
                continue;
            }
            try {
                portForward.close();
            } catch (IOException e) {
                // the phase is over anyway
            }
        }
        if (kubernetes != null) {
            kubernetes.close();
        }
    }

    private static Deployment deployment(KubernetesClient kubernetes, String name) {
        return kubernetes.apps().deployments().inNamespace(CORE_NAMESPACE).withName(name).get();
    }
}
