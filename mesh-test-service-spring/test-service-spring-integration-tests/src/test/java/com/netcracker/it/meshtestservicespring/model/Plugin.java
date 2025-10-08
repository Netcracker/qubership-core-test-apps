package com.netcracker.it.meshtestservicespring.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class Plugin {
    private String name;
    @JsonProperty("isInitialized")
    private boolean isInitialized;
    private String url;
}
