package com.netcracker.cloud.meshtestservicespring.model;

import lombok.Getter;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Getter
public class LuaFilterTraceResponse {

    private final String path;
    private final Map<String, List<String>> headers;

    public LuaFilterTraceResponse(String path, Map<String, List<String>> headers) {
        this.path = path;
        this.headers = headers;
    }

    public static LuaFilterTraceResponse fromRequest(jakarta.servlet.http.HttpServletRequest request) {
        String path = request.getRequestURI();
        Map<String, List<String>> headers = Collections.list(request.getHeaderNames())
                .stream()
                .collect(Collectors.toMap(
                        name -> name,
                        name -> Collections.list(request.getHeaders(name)),
                        (left, right) -> left));
        return new LuaFilterTraceResponse(path, headers);
    }
}
