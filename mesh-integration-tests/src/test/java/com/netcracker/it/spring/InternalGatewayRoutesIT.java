package com.netcracker.it.spring;

import com.netcracker.cloud.junit.cloudcore.extension.annotations.Cloud;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.EnableExtension;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.netcracker.it.spring.Const.INTERNAL_GW_SERVICE_NAME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Istio merges the routes of one Service parent into a single VirtualService only when their parentRefs agree on
 * the port; otherwise the waypoint takes the VirtualServices in creation order, and the catch-all fallback route
 * of core-mesh-config shadows every route created after it. So no route may set a port on internal-gateway-service.
 */
@EnableExtension
@Slf4j
@Tag("InternalGatewayRoutes")
class InternalGatewayRoutesIT {

    @Cloud
    private static KubernetesClient client;

    @Test
    void internalGatewayServiceParentRefsHaveNoPort() {
        List<GenericKubernetesResource> routes;
        try {
            routes = client.genericKubernetesResources("gateway.networking.k8s.io/v1", "HTTPRoute")
                    .inNamespace(client.getNamespace()).list().getItems();
        } catch (KubernetesClientException e) {
            // no Gateway API CRDs: not an Istio installation
            assumeTrue(e.getCode() != 404, "HTTPRoute CRD is not installed");
            throw e;
        }

        List<String> withPort = routes.stream()
                .flatMap(route -> parentRefs(route).stream()
                        .filter(ref -> "Service".equals(ref.get("kind"))
                                && INTERNAL_GW_SERVICE_NAME.equals(ref.get("name"))
                                && (ref.get("group") == null || "".equals(ref.get("group")))
                                && ref.get("port") != null)
                        .map(ref -> route.getMetadata().getName() + " (port " + ref.get("port") + ")"))
                .toList();

        assertEquals(List.of(), withPort,
                "HTTPRoutes with a port in the %s parentRef: the waypoint lets the fallback route shadow them"
                        .formatted(INTERNAL_GW_SERVICE_NAME));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> parentRefs(GenericKubernetesResource route) {
        Object spec = route.getAdditionalProperties().get("spec");
        if (!(spec instanceof Map<?, ?> specMap) || !(specMap.get("parentRefs") instanceof List<?> refs)) {
            return List.of();
        }
        return (List<Map<String, Object>>) refs;
    }
}
