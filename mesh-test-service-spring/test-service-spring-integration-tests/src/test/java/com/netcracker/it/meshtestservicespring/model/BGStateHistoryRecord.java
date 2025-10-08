package com.netcracker.it.meshtestservicespring.model;

import lombok.Data;

@Data
public class BGStateHistoryRecord {
    String controllerNamespace;
    NamespaceState originNamespace;
    NamespaceState peerNamespace;
    String updateTime;
}