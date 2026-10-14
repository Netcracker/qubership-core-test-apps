package com.netcracker.it.common.model;

import lombok.Data;

@Data
public class TraceResponse {
    String serviceName;
    String familyName;
    String namespace;
    String version;
    String podId;

    String requestHost;
    String serverHost;
    String remoteAddr;
    String path;
    String method;
    String xversion;
    String xVersionName;
    String xRequestId;

    String requestMessage;
}
