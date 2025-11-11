package com.netcracker.cloud.meshspringtestapp.service;

import com.netcracker.cloud.meshtestservicespring.service.EgressService;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;


public class EgressServiceTest {

    @Test
    public void testHelloService() throws Exception {
        MockWebServer mockWebServer = new MockWebServer();
        mockWebServer.enqueue(new MockResponse().setResponseCode(503));
        mockWebServer.enqueue(new MockResponse().setResponseCode(200).setBody("testAnswer"));
        mockWebServer.start();

        EgressService egressService = new EgressService(mockWebServer.url("/api/v3/control-plane/versions/registry").uri().toString());
        ReflectionTestUtils.setField(egressService, "m2mWebClient", WebClient.create());

        String result = egressService.callEgress();
        Assert.assertEquals("Egress answered:testAnswer", result);

        mockWebServer.shutdown();
    }
}