package com.netcracker.it.meshtestservicequarkus;

import com.google.gson.Gson;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.*;
//import com.netcracker.cloud.junit.cloudcore.extension.client.KubernetesClientFactory;
//import com.netcracker.cloud.junit.cloudcore.service.ITHelper;
import com.netcracker.it.meshtestservicequarkus.model.TraceResponse;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URL;

import static com.netcracker.it.meshtestservicequarkus.CommonOperations.*;
import static org.junit.jupiter.api.Assertions.*;

@EnableExtension
@Tag("Mesh")
public class HttpIT {

//    @Named(PUBLIC_GW_SERVICE_NAME)
//    @Scheme("http")
//    @PortForward
    @PortForward(serviceName = @Value(PUBLIC_GW_SERVICE_NAME))
    private static URL publicGWServerUrl;

//    @Named(INTERNAL_GW_SERVICE_NAME)
//    @Scheme("http")
//    @PortForward
    @PortForward(serviceName = @Value(INTERNAL_GW_SERVICE_NAME))
    private static URL internalGWServerUrl;

//    @Named(SERVICE_NAME)
//    @Scheme("http")
//    @PortForward
    @PortForward(serviceName = @Value(SERVICE_NAME))
    private static URL compositeGWServerUrl;

//    @Client
//    private static KubernetesClientFactory platformClient;

//    private static ITHelper itHelper;

    @BeforeAll
    public static void init() throws Exception {
        assertNotNull(publicGWServerUrl);
        assertNotNull(internalGWServerUrl);
//        assertNotNull(platformClient);
//        itHelper = new ITHelper(internalGWServerUrl, platformClient);
//        itHelper.loginAsCloudAdmin();
    }

    @Test
    public void testRouteRegisteredByLib() throws IOException {
        Request request = new Request.Builder()
                .url(publicGWServerUrl + "/api/v1/" + SERVICE_NAME + "/hello")
//                .addHeader("Authorization", "Bearer " + itHelper.getCloudAdminToken())
                .get()
                .build();
        try (Response response = okHttpClient.newCall(request).execute()) {
            assertEquals(200, response.code());
            String jsonResponse = response.body().string();
            TraceResponse traceResponse = new Gson().fromJson(jsonResponse, TraceResponse.class);
            assertEquals(SERVICE_NAME, traceResponse.getFamilyName());
        }
    }

    @Test//Send not existed deployment version
    public void testContextPropagation() throws IOException {
        Request request = new Request.Builder()
                .url(publicGWServerUrl + "/api/v1/" + SERVICE_NAME + "/hello/go")
//                .addHeader("Authorization", "Bearer " + itHelper.getCloudAdminToken())
                .addHeader("X-Version","v999")
                .get()
                .build();
        try (Response response = okHttpClient.newCall(request).execute()) {
            assertEquals(200, response.code());
            String jsonResponse = response.body().string();
            TraceResponse traceResponse = new Gson().fromJson(jsonResponse, TraceResponse.class);
            assertEquals("mesh-test-service-go", traceResponse.getFamilyName());
            assertEquals("v999", traceResponse.getXversion());
        }
    }

    @Test
    public void testRouteToGoService() throws IOException {
        Request request = new Request.Builder()
                .url(publicGWServerUrl + "/api/v1/" + SERVICE_NAME + "/hello/go")
//                .addHeader("Authorization", "Bearer " + itHelper.getCloudAdminToken())
                .get()
                .build();
        try (Response response = okHttpClient.newCall(request).execute()) {
            assertEquals(200, response.code());
            String jsonResponse = response.body().string();
            TraceResponse traceResponse = new Gson().fromJson(jsonResponse, TraceResponse.class);
            assertEquals("mesh-test-service-go", traceResponse.getFamilyName());
        }
    }
}
