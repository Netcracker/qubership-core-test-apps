package com.netcracker.it.common;

import com.netcracker.cloud.security.core.utils.tls.TlsUtils;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.util.concurrent.TimeUnit;

@Slf4j
public class HttpClient {
    public static OkHttpClient okHttpClient = new OkHttpClient.Builder()
            .addInterceptor(withRetryOnServiceUnavailableOrTimeout())
            .readTimeout(60, TimeUnit.SECONDS)
            .connectTimeout(60, TimeUnit.SECONDS)
            .sslSocketFactory(TlsUtils.getSslContext().getSocketFactory(), TlsUtils.getTrustManager())
            .build();

    private static Interceptor withRetryOnServiceUnavailableOrTimeout() {
        return chain -> {
            Request request = chain.request();
            Response response = null;
            java.io.InterruptedIOException ex = null;

            long deadline = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(2);
            while (System.currentTimeMillis() < deadline) {
                closeResponse(response);
                try {
                    response = chain.proceed(request);
                    log.info("response code {} for request to {} {}", response.code(), request.method(), request.url());
                    ex = null;
                    if (!isRetryableFailureStatusCode(response)) {
                        return response;
                    }
                } catch (java.io.InterruptedIOException e) {
                    log.error("Got Interrupted IO Exception for request to {}", request.url(), ex);
                    ex = e;
                }
                sleepForSeconds(3);
            }

            if (ex != null) {
                throw ex;
            }

            return response;
        };
    }

    private static boolean isRetryableFailureStatusCode(Response response) {
        return response == null || response.code() == 503
                || response.code() == 521
                || response.code() == 504;
    }

    private static void sleepForSeconds(int i) {
        try {
            TimeUnit.SECONDS.sleep(3);
        } catch (InterruptedException intEx) {
            log.warn("Interrupted during sleep", intEx);
            throw new RuntimeException("Interrupted during sleep", intEx);
        }
    }

    private static void closeResponse(Response response) {
        if (response != null) {
            try {
                response.close();
            } catch (Exception e1) {
                log.warn("Cannot close response", e1);
            }
        }
    }
}
