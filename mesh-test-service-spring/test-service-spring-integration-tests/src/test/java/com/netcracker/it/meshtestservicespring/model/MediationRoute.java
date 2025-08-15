package com.netcracker.it.meshtestservicespring.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.fabric8.kubernetes.api.model.networking.v1beta1.Ingress;
//import io.fabric8.openshift.api.model.Route;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class MediationRoute extends MediationMetadata {
    private RouteSpec spec;

//    public MediationRoute(Route route) {
//        metadata = new Metadata();
//        metadata.setAnnotations(route.getMetadata().getAnnotations());
//        metadata.setKind(route.getKind());
//        metadata.setLabels(route.getMetadata().getLabels());
//        metadata.setName(route.getMetadata().getName());
//        metadata.setNamespace(route.getMetadata().getNamespace());
//        spec = new RouteSpec();
//        spec.host = route.getSpec().getHost();
//        spec.path = route.getSpec().getPath();
//        spec.service = new Target();
//        spec.service.name = route.getSpec().getTo().getName();
//    }

    public MediationRoute(Ingress ingress) {
        metadata = new Metadata();
        metadata.setAnnotations(ingress.getMetadata().getAnnotations());
        metadata.setKind(ingress.getKind());
        metadata.setLabels(ingress.getMetadata().getLabels());
        metadata.setName(ingress.getMetadata().getName());
        metadata.setNamespace(ingress.getMetadata().getNamespace());
        spec = new RouteSpec();
        if (ingress.getSpec() != null && !ingress.getSpec().getRules().isEmpty()) {
            spec.host = ingress.getSpec().getRules().get(0).getHost();
            if (ingress.getSpec().getRules().get(0).getHttp() != null && 
                !ingress.getSpec().getRules().get(0).getHttp().getPaths().isEmpty()) {
                spec.path = ingress.getSpec().getRules().get(0).getHttp().getPaths().get(0).getPath();
            }
        }
        spec.service = new Target();
        if (ingress.getSpec() != null && !ingress.getSpec().getRules().isEmpty() &&
            ingress.getSpec().getRules().get(0).getHttp() != null && 
            !ingress.getSpec().getRules().get(0).getHttp().getPaths().isEmpty() &&
            ingress.getSpec().getRules().get(0).getHttp().getPaths().get(0).getBackend() != null &&
            ingress.getSpec().getRules().get(0).getHttp().getPaths().get(0).getBackend().getServiceName() != null) {
            spec.service.name = ingress.getSpec().getRules().get(0).getHttp().getPaths().get(0).getBackend().getServiceName();
        }
    }

    @Data
    public static class RouteSpec {
        private String host;
        private String path;
        @JsonProperty("to")
        private Target service;
    }

    @Data
    public static class Target {
        private String name;
    }
}
