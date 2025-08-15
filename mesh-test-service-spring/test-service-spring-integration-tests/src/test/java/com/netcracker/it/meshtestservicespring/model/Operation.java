package com.netcracker.it.meshtestservicespring.model;

import java.io.Serializable;

public enum Operation implements Serializable {
    WARMUP("warmup"),
    PROMOTE("promote"),
    ROLLBACK("rollback"),
    COMMIT("commit"),
    TERMINATE("terminate");

    private final String name;

    Operation(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    @Override
    public String toString() {
        return name;
    }

}
