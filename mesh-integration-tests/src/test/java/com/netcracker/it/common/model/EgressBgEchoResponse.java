package com.netcracker.it.common.model;

import com.google.gson.annotations.SerializedName;
import lombok.Getter;
import lombok.ToString;

@Getter
@ToString
public class EgressBgEchoResponse {

    /** Name of the endpoint that answered: {@code bg-prod} or {@code bg-stub}. */
    @SerializedName("endpoint")
    private String endpoint;

    /** Host header the endpoint received. */
    @SerializedName("host")
    private String host;

    /** Request URI the endpoint received, after the prefix rewrite. */
    @SerializedName("uri")
    private String uri;

    /** {@code x-version-name} header as received, empty when absent. */
    @SerializedName("xVersionName")
    private String xVersionName;
}
