package com.netcracker.it.spring;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import org.apache.commons.lang3.StringUtils;

import java.io.Serializable;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

import static java.util.function.UnaryOperator.identity;

public enum ProcessStatus implements Serializable {
    NOT_STARTED("not started"),
    IN_PROGRESS("in progress"),
    COMPLETED("completed"),
    TERMINATED("terminated"),
    FAILED("failed");

    private static final Map<String, ProcessStatus> ENUM_MAP = Arrays.stream(ProcessStatus.values())
            .collect(Collectors.toMap(ProcessStatus::getName, identity()));

    private final String name;

    ProcessStatus(String name) {
        this.name = name;
    }

    @JsonCreator
    public static ProcessStatus fromString(String name) {
        if (StringUtils.isBlank(name)) {
            return null;
        }

        ProcessStatus processStatus = ENUM_MAP.get(name.toLowerCase());
        if (processStatus == null) {
            throw new IllegalArgumentException(String.format("Can not found enum ProcessStatus with name: %s", name.toLowerCase()));
        }
        return processStatus;
    }

    @JsonValue
    public String getName() {
        return name;
    }

    @Override
    public String toString() {
        return name;
    }

}
