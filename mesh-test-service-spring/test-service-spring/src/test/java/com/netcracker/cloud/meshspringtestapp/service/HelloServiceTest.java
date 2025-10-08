package com.netcracker.cloud.meshspringtestapp.service;

import com.netcracker.cloud.meshtestservicespring.service.HelloService;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;


public class HelloServiceTest {

    @Test
    public void testHelloService() {
        HelloService helloService = new HelloService();
        org.springframework.test.util.ReflectionTestUtils.setField(helloService, "deploymentVersion", "v1");
        org.springframework.test.util.ReflectionTestUtils.setField(helloService, "familyName", "mesh-test-service-spring");
        org.springframework.test.util.ReflectionTestUtils.setField(helloService, "namespace", "test-namespace");
        String hello = helloService.hello(new MockHttpServletRequest());
        Assert.assertTrue(hello.startsWith("{\"serviceName\":\"mesh-test-service-spring-v1\",\"familyName\":\"mesh-test-service-spring\",\"namespace\":\"test-namespace\",\"version\":\"v1\""));
    }

    @Test
    public void testHelloQuarkusService() {
        HelloService helloService = new HelloService();
        org.springframework.test.util.ReflectionTestUtils.setField(helloService, "deploymentVersion", "v1");
        org.springframework.test.util.ReflectionTestUtils.setField(helloService, "familyName", "mesh-test-service-spring");
        org.springframework.test.util.ReflectionTestUtils.setField(helloService, "namespace", "test-namespace");

        String expectedAnswer = "answer";
        WebClient webClientMock = getMockWebClient("http://mesh-test-service-quarkus:8080/api/v1/mesh-test-service-quarkus/hello", expectedAnswer);
        org.springframework.test.util.ReflectionTestUtils.setField(helloService, "m2mWebClient", webClientMock);

        String hello = helloService.helloQuarkus("");
        Assert.assertEquals(expectedAnswer, hello);
    }

    private WebClient getMockWebClient(String url, String answer) {
        WebClient webClient = mock(WebClient.class);
        WebClient.RequestHeadersUriSpec requestHeadersUriSpec = mock(WebClient.RequestHeadersUriSpec.class);
        WebClient.RequestHeadersSpec requestHeadersSpec = mock(WebClient.RequestHeadersSpec.class);
        WebClient.ResponseSpec responseSpecMock = mock(WebClient.ResponseSpec.class);

        when(webClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(url)).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpecMock);
        when(responseSpecMock.bodyToMono(String.class)).thenReturn(Mono.just(answer));

        return webClient;
    }

}