package com.netcracker.it.meshtestservicespring.model;

import lombok.Data;

@Data
public class BGState {
    private String controllerNamespace;
    private NamespaceState originNamespace;
    private NamespaceState peerNamespace;
    private String updateTime;
}