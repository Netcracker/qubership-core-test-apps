package com.netcracker.it.go;

import com.netcracker.cloud.security.core.utils.tls.TlsUtils;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Slf4j
public class CommonOperations {
    public static final MediaType JSON
            = MediaType.parse("application/json; charset=utf-8");

    public static final String SERVICE_NAME = "mesh-test-service-go";
    public static final String INTERNAL_GW_SERVICE_NAME = "internal-gateway-service";
    public static final String PUBLIC_GW_SERVICE_NAME = "public-gateway-service";

    public static OkHttpClient okHttpClient = new OkHttpClient.Builder()
            .readTimeout(60, TimeUnit.SECONDS)
            .connectTimeout(60, TimeUnit.SECONDS)
            .sslSocketFactory(TlsUtils.getSslContext().getSocketFactory(), TlsUtils.getTrustManager())
            .build();

    public static OkHttpClient retryOkHttpClient = new OkHttpClient.Builder()
            .addInterceptor(chain -> {
                Request request = chain.request();
                Response response = chain.proceed(request);

                int tryCount = 0;
                int maxLimit = 5;
                while (!response.isSuccessful() && tryCount < maxLimit) {
                    log.info("Request to idp is not successful. {}", response);
                    tryCount++;
                    if(tryCount == maxLimit) {
                        throw new RuntimeException("Idp is not available");
                    }
                    try {
                        Thread.sleep(15000);
                    } catch (InterruptedException e) {
                        log.error("Something went wrong", e);
                    }
                    response = chain.proceed(request);
                }
                return response;
            })
            .readTimeout(60, TimeUnit.SECONDS)
            .connectTimeout(60, TimeUnit.SECONDS)
            .sslSocketFactory(TlsUtils.getSslContext().getSocketFactory(), TlsUtils.getTrustManager())
            .build();
}
