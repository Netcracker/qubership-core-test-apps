package com.netcracker.it.spring.model;

import lombok.Data;

import java.util.ArrayList;

@Data
public class BGContextResponse {
    BGContext BGContext;
    ArrayList<DetailedState> detailedState;
}