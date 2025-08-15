package com.netcracker.it.meshtestservicespring.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BgLock {
    private boolean locked;
    private String lockedWhen;
    private String lockDetails;
}
