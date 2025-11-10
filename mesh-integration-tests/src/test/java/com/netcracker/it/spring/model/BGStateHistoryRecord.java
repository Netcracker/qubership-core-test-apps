package com.netcracker.it.spring.model;

import lombok.Data;

@Data
public class BGStateHistoryRecord {
    String controllerNamespace;
    NamespaceState originNamespace;
    NamespaceState peerNamespace;
    String updateTime;
}