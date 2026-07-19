package com.mieai.qqbot.protocol.auth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Response returned by the app access token endpoint. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AccessTokenResponse(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("expires_in") long expiresIn,
        @JsonProperty("code") Integer code,
        @JsonProperty("message") String message) {

    /** Retains the successful response constructor used before business errors were modeled. */
    public AccessTokenResponse(String accessToken, long expiresIn) {
        this(accessToken, expiresIn, null, null);
    }

    @Override
    public String toString() {
        return "AccessTokenResponse[accessToken=<redacted>, expiresIn="
                + expiresIn
                + ", code="
                + code
                + ", message=<redacted>]";
    }
}
