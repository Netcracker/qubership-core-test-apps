package com.netcracker.it.common.model;

import com.google.gson.annotations.SerializedName;
import lombok.Getter;

/**
 * What the simulated external HTTPS site saw. Answered by the egress-tls-echo nginx
 * on every request, so a test can tell what the egress gateway actually originated:
 * the SNI it offered, the authority it rewrote the request to, and the client
 * certificate it presented, if any.
 */
@Getter
public class EgressEchoResponse {

    /** SNI the gateway offered while opening the TLS connection. */
    @SerializedName("sni")
    private String sni;

    /** Host header the gateway sent, after any host rewrite. */
    @SerializedName("host")
    private String host;

    /** Request URI the site received, after any prefix rewrite. */
    @SerializedName("uri")
    private String uri;

    /** {@code SUCCESS} for a verified client certificate, {@code NONE} when none was asked for. */
    @SerializedName("clientVerify")
    private String clientVerify;

    /** Subject DN of the client certificate, empty when there is none. */
    @SerializedName("clientDn")
    private String clientDn;

    /** {@code Origin} header, expected to be stripped by the egress route. */
    @SerializedName("origin")
    private String origin;

    /** {@code Authorization} header, expected to be stripped by the egress route. */
    @SerializedName("authorization")
    private String authorization;

    /** {@code tenant-id} header, matched on by the verified route. */
    @SerializedName("tenantId")
    private String tenantId;
}
