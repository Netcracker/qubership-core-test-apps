package com.netcracker.it.spring.model;

import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
public class ProxyResponse {
    private int status;
    private Map<String, List<String>> headers;
}
