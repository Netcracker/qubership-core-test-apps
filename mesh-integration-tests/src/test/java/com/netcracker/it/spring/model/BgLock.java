package com.netcracker.it.spring.model;

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
