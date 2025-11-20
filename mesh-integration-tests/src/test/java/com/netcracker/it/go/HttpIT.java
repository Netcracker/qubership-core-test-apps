package com.netcracker.it.go;

import com.google.gson.Gson;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.EnableExtension;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.PortForward;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.Value;
import com.netcracker.it.go.model.TraceResponse;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URL;

import static com.netcracker.it.go.CommonOperations.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@EnableExtension
@Tag("Mesh")
public class HttpIT {

    @PortForward(serviceName = @Value(PUBLIC_GW_SERVICE_NAME))
    private static URL publicGWServerUrl;

    @PortForward(serviceName = @Value(INTERNAL_GW_SERVICE_NAME))
    private static URL internalGWServerUrl;

    @PortForward(serviceName = @Value(SERVICE_NAME))
    private static URL compositeGWServerUrl;

    @BeforeAll
    public static void init() throws Exception {
        assertNotNull(publicGWServerUrl);
        assertNotNull(internalGWServerUrl);
        assertNotNull(compositeGWServerUrl);
    }

    @Test
    public void testRouteRegisteredByLib() throws IOException {
        Request request = new Request.Builder()
                .url(publicGWServerUrl + "/api/v1/" + SERVICE_NAME + "/hello")
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
                .url(publicGWServerUrl + "/api/v1/" + SERVICE_NAME + "/hello/spring")
                .addHeader("X-Version","v999")
                .get()
                .build();
        try (Response response = okHttpClient.newCall(request).execute()) {
            assertEquals(200, response.code());
            String jsonResponse = response.body().string();
            TraceResponse traceResponse = new Gson().fromJson(jsonResponse, TraceResponse.class);
            assertEquals("mesh-test-service-spring", traceResponse.getFamilyName());
            assertEquals("v999", traceResponse.getXversion());
        }
    }

    @Test
    public void testRouteToSpringService() throws IOException {
        Request request = new Request.Builder()
                .url(publicGWServerUrl + "/api/v1/" + SERVICE_NAME + "/hello/spring")
                .get()
                .build();
        try (Response response = okHttpClient.newCall(request).execute()) {
            assertEquals(200, response.code());
            String jsonResponse = response.body().string();
            TraceResponse traceResponse = new Gson().fromJson(jsonResponse, TraceResponse.class);
            assertEquals("mesh-test-service-spring", traceResponse.getFamilyName());
        }
    }
}
