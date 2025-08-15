package com.netcracker.it.meshtestservicespring.utils;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class NodePortConfig {
    private Map<Integer, NodePortConfigInternal> config;
    private List<String> externalIPs;

    public NodePortConfig(List<String> externalIPs) {
        this.externalIPs = externalIPs;
        this.config = new HashMap<>();
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NodePortConfigInternal {
        private String name;
        private int nodePort;
        private String protocol;
    }
}
