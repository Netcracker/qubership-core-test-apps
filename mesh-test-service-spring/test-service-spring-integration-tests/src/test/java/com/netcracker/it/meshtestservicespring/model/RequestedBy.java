package com.netcracker.it.meshtestservicespring.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RequestedBy {
    private String username;
    private String email;
}
