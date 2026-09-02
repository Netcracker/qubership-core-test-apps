package com.netcracker.cloud.core.consullogin.stand;

import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeBuilder;
import io.fabric8.kubernetes.api.model.VolumeMount;
import io.fabric8.kubernetes.api.model.VolumeMountBuilder;

/**
 * The projected service account token a pod presents to a Kubernetes auth method. Its audience is the one the API
 * server of the stand was started with, and Consul accepts no other.
 */
public final class ProjectedToken {

    public static final String AUDIENCE = "netcracker";
    public static final String MOUNT_PATH = "/var/run/secrets/tokens/netcracker";

    private static final String VOLUME_NAME = "netcracker-token";

    private ProjectedToken() {
    }

    public static Volume volume() {
        return new VolumeBuilder()
                .withName(VOLUME_NAME)
                .withNewProjected()
                .addNewSource()
                .withNewServiceAccountToken()
                .withAudience(AUDIENCE)
                .withExpirationSeconds(3600L)
                .withPath("token")
                .endServiceAccountToken()
                .endSource()
                .endProjected()
                .build();
    }

    public static VolumeMount mount() {
        return new VolumeMountBuilder()
                .withName(VOLUME_NAME)
                .withMountPath(MOUNT_PATH)
                .withReadOnly(true)
                .build();
    }
}
