package com.netcracker.it.spring.model;

import lombok.Data;

@Data
public class BGPluginHistoryRecord {
    BGStateHistoryRecord BGState;
    String operation;
    CloudId cloudId;
    RequestedBy requestedBy;
}
