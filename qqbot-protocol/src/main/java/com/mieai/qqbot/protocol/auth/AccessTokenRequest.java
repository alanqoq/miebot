package com.mieai.qqbot.protocol.auth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Request body for obtaining an app access token. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AccessTokenRequest(
        @JsonProperty("appId") String appId,
        @JsonProperty("clientSecret") String clientSecret) {

    @Override
    public String toString() {
        return "AccessTokenRequest[appId=" + appId + ", clientSecret=<redacted>]";
    }
}
