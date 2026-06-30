package com.netcracker.it.spring.lua;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.netcracker.it.spring.IntegrationTestSupport;
import com.netcracker.it.spring.model.LuaFilterTraceResponse;
import lombok.extern.slf4j.Slf4j;
import org.awaitility.core.ConditionTimeoutException;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.awaitility.Awaitility.await;

@Slf4j
public final class LuaFilterTestHelper {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Pattern UUID_FROM_PATH = Pattern.compile(".*/([a-z0-9-]+)$");
    private static final long READY_TIMEOUT_MS = 120_000;

    private LuaFilterTestHelper() {}

    public static LuaFilterTraceResponse waitUntilGatewayReady(String gatewayServiceName, String probePath) throws Exception {
        String url = IntegrationTestSupport.inClusterServiceUrl(gatewayServiceName, probePath);
        IntegrationTestSupport.waitUntilHttpOk(
                "lua-filter via " + gatewayServiceName + " " + probePath,
                READY_TIMEOUT_MS,
                () -> IntegrationTestSupport.getHttpStatus(url));
        LuaFilterTraceResponse response = waitUntilLuaFilterActive(probePath, () -> fetchTrace(url));
        log.info("Lua filter active via {}", gatewayServiceName);
        return response;
    }

    private static TraceFetchResult fetchTrace(String url) throws Exception {
        String output = IntegrationTestSupport.executeInClusterPod("curl", "-s", "-w", "\n%{http_code}", url);
        if (output == null || output.isBlank()) {
            return new TraceFetchResult(IntegrationTestSupport.STATUS_UNKNOWN, null);
        }
        int lastNewline = output.lastIndexOf('\n');
        if (lastNewline < 0) {
            return new TraceFetchResult(IntegrationTestSupport.STATUS_UNKNOWN, null);
        }
        String rawBody = output.substring(0, lastNewline).trim();
        String statusLine = output.substring(lastNewline + 1).trim();
        int httpStatus;
        try {
            httpStatus = Integer.parseInt(statusLine);
        } catch (NumberFormatException e) {
            return new TraceFetchResult(IntegrationTestSupport.STATUS_UNKNOWN, null);
        }
        if (httpStatus != 200 || rawBody.isEmpty()) {
            return new TraceFetchResult(httpStatus, null);
        }
        try {
            return new TraceFetchResult(httpStatus, OBJECT_MAPPER.readValue(rawBody, LuaFilterTraceResponse.class));
        } catch (Exception e) {
            log.debug("Failed to parse trace JSON from {}: {}", url, e.getMessage());
            return new TraceFetchResult(httpStatus, null);
        }
    }

    private static LuaFilterTraceResponse waitUntilLuaFilterActive(String probePath, TraceFetcher fetcher)
            throws Exception {
        String expectedUuid = uuidFromProbePath(probePath);
        AtomicReference<TraceFetchResult> lastResult = new AtomicReference<>(
                new TraceFetchResult(IntegrationTestSupport.STATUS_UNKNOWN, null));
        try {
            await()
                    .atMost(Duration.ofMillis(READY_TIMEOUT_MS))
                    .pollInterval(Duration.ofMillis(IntegrationTestSupport.POLL_INTERVAL_MS))
                    .until(() -> {
                        TraceFetchResult result = fetcher.fetch();
                        lastResult.set(result);
                        LuaFilterTraceResponse response = result.body();
                        return response != null
                                && probePath.equals(response.getPath())
                                && expectedUuid.equals(response.getHeader("X-Uuid"));
                    });
        } catch (ConditionTimeoutException e) {
            TraceFetchResult result = lastResult.get();
            LuaFilterTraceResponse response = result.body();
            String details = response == null
                    ? "http=" + result.httpStatus() + ", no trace body"
                    : "http=" + result.httpStatus() + ", path=" + response.getPath()
                    + ", x-uuid=" + response.getHeader("X-Uuid");
            throw new IllegalStateException("Lua filter on " + probePath + " not ready within "
                    + READY_TIMEOUT_MS + "ms (" + details + ")", e);
        }
        return lastResult.get().body();
    }

    private static String uuidFromProbePath(String probePath) {
        Matcher matcher = UUID_FROM_PATH.matcher(probePath);
        return matcher.find() ? matcher.group(1) : "";
    }

    @FunctionalInterface
    private interface TraceFetcher {
        TraceFetchResult fetch() throws Exception;
    }

    private record TraceFetchResult(int httpStatus, LuaFilterTraceResponse body) {}
}
