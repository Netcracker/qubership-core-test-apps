package com.netcracker.it.spring;

import com.netcracker.cloud.junit.cloudcore.extension.annotations.Cloud;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.EnableExtension;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceList;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static com.netcracker.it.spring.CommonOperations.assertWithTimeout;
import static com.netcracker.it.spring.Const.K8S_SERVICE_TYPE_VALUE;
import static org.junit.jupiter.api.Assertions.assertEquals;

@EnableExtension
@Slf4j
@Tag("Headless")
public class HeadlessIT {
    @Cloud
    private static KubernetesClient client;

    @Test
    public void checkServicesType(){
        assertWithTimeout(() -> {
            ServiceList serviceList = client.services().inNamespace(client.getNamespace()).list();
            List<Service> wrongServiceList = serviceList.getItems().stream().filter(service -> {
                return ((K8S_SERVICE_TYPE_VALUE.equals("HEADLESS")) && !service.getSpec().getClusterIP().equals("None")) || (K8S_SERVICE_TYPE_VALUE.equals("CLUSTER_IP") && (service.getSpec().getClusterIP().equals("None")));
            }).collect(Collectors.toList());
            assertEquals(0, wrongServiceList.size(), printMessage(wrongServiceList));
        });
    }

    String printMessage(List<Service> wrongServiceList) {
        String message = String.format("%d services have wrong type, should be:%s\n", wrongServiceList.size(), K8S_SERVICE_TYPE_VALUE);
        for (Service service : wrongServiceList) {
            message = message + "\n" + service.getMetadata().getName();
        }
        return message;
    }
}
