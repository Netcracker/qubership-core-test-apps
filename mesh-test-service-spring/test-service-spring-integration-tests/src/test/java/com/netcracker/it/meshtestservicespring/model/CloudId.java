package com.netcracker.it.meshtestservicespring.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CloudId {
    private String tenant;
    private String cloudName;
}
