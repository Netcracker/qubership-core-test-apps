package com.netcracker.it.meshtestservicespring;

import com.netcracker.cloud.junit.cloudcore.extension.annotations.Cloud;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.EnableExtension;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.PortForward;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.Value;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceList;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URL;
import java.util.List;

import static com.netcracker.it.meshtestservicespring.Const.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

@EnableExtension
@Slf4j
@Disabled
public class SpecificBGCompositeIT {

    @PortForward(serviceName = @Value(PUBLIC_GW_SERVICE_NAME))
    private static URL publicGWServerUrlSatellite;

    @Cloud
    private static KubernetesClient platformClientSatellite;

    @BeforeAll
    public static void initParentClass() throws Exception {
    }

    @Test
    @Tag("e2e-phase:test[satellite]:standalone/[baseline]:standalone")
    public void testSatelliteExternalNamesBeforeBGInitialized() throws IOException {
        log.info("testSatelliteExternalNamesBeforeBGInitialized started");
        List<Service> services = getServicesByExternalNamespace(BASELINE_ORIGIN_NAMESPACE);
        assertEquals(SATELLITE_CHECKING_SERVICES_NAMES.size(), services.size());
    }

    @Test
    @Tag("e2e-phase:test[satellite]:standalone/[baseline]:bgd{O{a}*P{i}*C}")
    public void testSatelliteExternalNamesAfterBGInitialized() throws IOException {
        log.info("testSatelliteExternalNamesAfterBGInitialized started");
        List<Service> services = getServicesByExternalNamespace(BASELINE_CONTROLLER_NAMESPACE);
        assertEquals(SATELLITE_CHECKING_SERVICES_NAMES.size(), services.size());
    }

    private List<Service> getServicesByExternalNamespace(String externalNamespace) {
        log.info("SATELLITE_ORIGIN_NAMESPACE: {}, externalNamespace: {}", SATELLITE_ORIGIN_NAMESPACE, externalNamespace);
        ServiceList serviceList = platformClientSatellite.services().inNamespace(SATELLITE_ORIGIN_NAMESPACE).list();

        List<Service> services = serviceList.getItems().stream()
                .filter(service -> SATELLITE_CHECKING_SERVICES_NAMES.contains(service.getMetadata().getName()))
                .filter(service -> service.getSpec().getExternalName() != null)
                .filter(service -> service.getSpec().getExternalName().startsWith(
                        service.getMetadata().getName() + "." + externalNamespace + ".")).toList();
        return services;
    }

}
