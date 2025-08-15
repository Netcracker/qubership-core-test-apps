package com.netcracker.it.meshtestservicespring.model;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Collections;
import java.util.Set;

@Data
@AllArgsConstructor
public class OperationRequest {
    private Set<String> tasksToSkip;

    public OperationRequest() {
        this.tasksToSkip = Collections.emptySet();
    }
}
