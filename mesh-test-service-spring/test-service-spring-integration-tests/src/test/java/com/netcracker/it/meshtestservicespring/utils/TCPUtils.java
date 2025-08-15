package com.netcracker.it.meshtestservicespring.utils;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.netcracker.it.meshtestservicespring.Const.NODE_IP_MAPPING_VALUE;

@Slf4j
public class TCPUtils {
    static final Map<String, String> nodeIPs = new HashMap<>();

    protected static ObjectMapper objectMapper;

    static {
        objectMapper = new ObjectMapper();
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        objectMapper.configure(SerializationFeature.INDENT_OUTPUT, true);

        if (!NODE_IP_MAPPING_VALUE.isEmpty()) {
            String[] nodeMappings = NODE_IP_MAPPING_VALUE.split(",");
            for (String nodeMapping : nodeMappings) {
                String[] splittedNodeMapping = nodeMapping.split(":");
                nodeIPs.put(splittedNodeMapping[0], splittedNodeMapping[1]);
            }
        }
    }

    public static List<String> getNodeIPs() {
        if (nodeIPs.isEmpty()) {
            log.warn("Node IPs not found");
            return Collections.emptyList();
        }
        return nodeIPs.values().stream().toList();
    }

    public static String getNodeIp(String name) {
        if (nodeIPs.isEmpty()) {
            log.warn("Node IPs not found");
            return null;
        }
        return nodeIPs.get(name);
    }
}
