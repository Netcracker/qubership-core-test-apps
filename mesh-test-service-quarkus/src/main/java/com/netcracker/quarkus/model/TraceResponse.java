package com.netcracker.quarkus.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.json.bind.annotation.JsonbProperty;
import lombok.Data;

@Data
public class TraceResponse {
    String serviceName;
    String familyName;
    String version;
    String namespace;
    String podId;

    String requestHost;
    String serverHost;
    String remoteAddr;
    String path;
    String method;
    String xversion;
    String xVersionName;

    // WA for jsonb
    @JsonProperty("xVersionName")
    @JsonbProperty("xVersionName")
    public void setXVersionName(String xVersionName) {
        this.xVersionName = xVersionName;
    }
}
