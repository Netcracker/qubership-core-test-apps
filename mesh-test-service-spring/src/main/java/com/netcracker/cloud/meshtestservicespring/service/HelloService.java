package com.netcracker.cloud.meshtestservicespring.service;

import com.google.gson.Gson;
import com.netcracker.cloud.meshtestservicespring.model.TraceResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.UUID;

import static com.netcracker.cloud.meshtestservicespring.utils.WebUtils.retryPolicy;

@Slf4j
public class HelloService {

    @Autowired()
    @Qualifier("m2mWebClient")
    private WebClient m2mWebClient;

    @Value("${cloud.microservice.bg_version}")
    private String deploymentVersion;

    @Value("${cloud.microservice.name}")
    private String familyName;

    @Value("${cloud.microservice.namespace}")
    private String namespace;

    @Value("#{environment.HOSTNAME}")
    private String hostName;

    private String podId = UUID.randomUUID().toString();

    public String hello(HttpServletRequest request) {
        log.info("hello");
        TraceResponse response = hello(request.getRemoteHost(), request.getHeader("X-Version"), request.getHeader("x-version-name"));
        log.info("Responding with service name:{} version:{}", response.getServiceName(), response.getVersion());
        return new Gson().toJson(response);
    }

    public TraceResponse hello() {
        TraceResponse response = new TraceResponse();
        response.setServiceName(familyName + "-" + deploymentVersion);
        response.setFamilyName(familyName);
        response.setNamespace(namespace);
        response.setVersion(deploymentVersion);
        response.setPodId(podId);
        response.setServerHost(hostName);
        return response;
    }

    public TraceResponse hello(String remoteHost, String xVersion, String xVersionName) {
        TraceResponse response = hello();
        response.setXversion(xVersion);
        response.setXVersionName(xVersionName);
        response.setRemoteAddr(remoteHost);
        return response;
    }

    public String helloQuarkus(String namespace) {
        log.info("hello from Quarkus");
        String messageUrl = "http://mesh-test-service-quarkus:8080/api/v1/mesh-test-service-quarkus/hello";
        if (namespace != null && !namespace.equals("") && !namespace.equals(" ")) {
            messageUrl = "http://mesh-test-service-quarkus." + namespace + ":8080/api/v1/mesh-test-service-quarkus/hello";
            log.info("hello from Quarkus from namespace:{}", namespace);
        }
        log.info("Sending request to '{}' to get message", messageUrl);
        String response = getRequest(messageUrl);
        log.info("Quarkus answered:{}", response);
        return response;
    }

    private String getRequest(String url) {
        WebClient.ResponseSpec responseSpec = m2mWebClient.get()
                .uri(url)
                .retrieve();

        return responseSpec
                .bodyToMono(String.class)
                .retryWhen(retryPolicy)
                .block();
    }
}
