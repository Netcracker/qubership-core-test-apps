package com.netcracker.it.meshtestservicespring.model.svt;

import com.netcracker.it.meshtestservicespring.CommonOperations;
import com.netcracker.it.meshtestservicespring.model.TraceResponse;
import com.netcracker.it.meshtestservicespring.utils.Utils;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.net.URL;

@Slf4j
@Data
public class RequestTestCase {
    private boolean withHeader;
    private String hostHeaderValue;
    private int expectedCode;
    private String expectedBackend;

    private int actualCode;
    private long tookMillis;
    private String response;

    public RequestTestCase(String hostHeaderValue, boolean withHeader, int expectedCode) {
        this(hostHeaderValue, withHeader, expectedCode, null);
    }

    public RequestTestCase(boolean withHeader, int expectedCode) {
        this(null, withHeader, expectedCode, null);
    }

    public RequestTestCase(boolean withHeader, int expectedCode, String expectedBackend) {
        this(null, withHeader, expectedCode, expectedBackend);
    }

    public RequestTestCase(String hostHeaderValue, boolean withHeader, int expectedCode, String expectedBackend) {
        this.hostHeaderValue = hostHeaderValue;
        this.withHeader = withHeader;
        this.expectedCode = expectedCode;
        this.expectedBackend = expectedBackend;
    }

    public boolean isSuccess() {
        final String requestDescription = "Request " + (withHeader ? "with x-version-name header" : "without x-version-name header");
        if (actualCode != expectedCode) {
            log.error("{} expected code {}, but got code {}", requestDescription, expectedCode, actualCode);
            return false;
        }
        if (actualCode == 200) {
            final TraceResponse traceResponse = Utils.GSON.fromJson(response, TraceResponse.class);
            if (expectedBackend.equals(traceResponse.getServiceName())) {
                log.debug("{} was routed to expected backend service {}", requestDescription, expectedBackend);
                return true;
            } else {
                log.error("{} was not routed to expected backend service {}", requestDescription, expectedBackend);
                return false;
            }
        }
        log.debug("{} succeeded", requestDescription);
        return true;
    }

    public void runTestRequest(final long replicationStartTime, final URL url) throws IOException {
        final String testUrl = url.toString() + "/api/v1/hello";
        final Request.Builder request = new Request.Builder().url(testUrl);
        if (withHeader) {
            request.addHeader("x-version-name", "candidate");
        }
//        request.addHeader("Authorization", "Bearer " + token);
        if (hostHeaderValue != null) {
            request.addHeader("Host", hostHeaderValue);
        }
        log.debug("Calling {}", testUrl);
        try (final Response response = CommonOperations.okHttpClient.newCall(request.build()).execute()) {
            actualCode = response.code();
            if (response.code() == expectedCode) {
                setTookMillis(System.currentTimeMillis() - replicationStartTime);
                if (response.code() == 200) {
                    final ResponseBody body = response.body();
                    if (body != null) {
                        this.response = body.string();
                    }
                }
            } else {
                throw new RuntimeException(String.format("Got unexpected response code %d from %s with x-version-name specified:%b", response.code(), url, isWithHeader()));
            }
        }
    }
}
