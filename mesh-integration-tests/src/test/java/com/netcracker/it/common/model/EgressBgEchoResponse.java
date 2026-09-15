package com.netcracker.it.common.model;

import com.google.gson.annotations.SerializedName;
import lombok.Getter;
import lombok.ToString;

/**
 * What one of the Blue/Green egress endpoints saw. Answered by the egress-bg-echo nginx
 * on every request, so a test can tell which endpoint the egress gateway chose and
 * whether the {@code x-version-name} header reached it.
 */
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
