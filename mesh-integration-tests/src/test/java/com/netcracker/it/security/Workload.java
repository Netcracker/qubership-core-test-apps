package com.netcracker.it.security;

import io.fabric8.kubernetes.api.model.PodSpec;
import io.fabric8.kubernetes.api.model.PodTemplateSpec;

/**
 * A workload under test: the pod template of a Deployment, StatefulSet, or DaemonSet.
 *
 * <p>Security requirements apply to the pod template rather than to live pods, because the template
 * is what the deployment owns and what a scanner can enforce before rollout.
 */
public record Workload(String kind, String name, String namespace, PodTemplateSpec template) {

    public PodSpec podSpec() {
        return template.getSpec();
    }

    /** Stable identifier such as {@code Deployment/istiod}, used in assertion messages. */
    public String id() {
        return kind + "/" + name;
    }
}
