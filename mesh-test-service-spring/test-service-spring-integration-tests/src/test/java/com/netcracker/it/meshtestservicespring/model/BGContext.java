package com.netcracker.it.meshtestservicespring.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.netcracker.it.meshtestservicespring.ProcessStatus;
import lombok.Data;

import java.util.List;

@Data
public class BGContext {
    @JsonProperty("BGState")
    private BGState BGState;
    private boolean domainInitialized;
    private boolean staasInitialized;
    private List<Plugin> plugins;
    private String latestVersion;
    private Operation operationInProgress;
    private String processID;
    private ProcessStatus processStatus;
    private String requestId;
}
