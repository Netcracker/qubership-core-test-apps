package com.netcracker.it.spring.model;

public enum StateName {
    IDLE("idle"), ACTIVE("active"), CANDIDATE("candidate"), LEGACY("legacy");

    private final String name;

    StateName(String name) {
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