package com.netcracker.it.spring.model.svt;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SvtThresholdsSet {
    private long maxReplicationTime;
    private long avgReplicationTime;
    private long maxRoutesPreparationTime;
    private long avgRoutesPreparationTime;
}
