package com.netcracker.it.go.model;

import lombok.Data;

@Data
public class TraceResponse {
    String serviceName;
    String familyName;
    String version;
    String podId;

    String requestHost;
    String serverHost;
    String remoteAddr;
    String path;
    String method;
    String xversion;
}
