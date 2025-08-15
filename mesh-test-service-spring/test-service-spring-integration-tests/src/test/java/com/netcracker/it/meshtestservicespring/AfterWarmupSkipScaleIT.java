package com.netcracker.it.meshtestservicespring;

import com.netcracker.cloud.junit.cloudcore.extension.annotations.Cloud;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.EnableExtension;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.Value;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static com.netcracker.it.meshtestservicespring.Const.*;

@EnableExtension
@Slf4j
@Tag("bg-e2e-phase:after-warmup-with-skip-scale-aps[baseline]")
public class AfterWarmupSkipScaleIT {

//    @Client
//    @Namespace(property = ORIGIN_NAMESPACE_ENV_NAME)
//    private static KubernetesClient platformClientOrigin;
    @Cloud(namespace = @Value(prop = ORIGIN_NAMESPACE_ENV_NAME))
    private static KubernetesClient platformClientOrigin;

    @Test
    @Disabled
    public void testCheckResourcesWarmup3() throws IOException {
        //After skip scale
        AfterWarmupIT.testCheckResources(ORIGIN_NAMESPACE, PEER_NAMESPACE, false, platformClientOrigin); //v3, v4
    }
}