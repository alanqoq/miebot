package com.mieai.qqbot.protocol.auth

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/** Response returned by the app access token endpoint. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class AccessTokenResponse(
    @JsonProperty("access_token") val accessToken: String?,
    @JsonProperty("expires_in") val expiresIn: Long,
    @JsonProperty("code") val code: Int? = null,
    @JsonProperty("message") val message: String? = null,
) {
    override fun toString(): String =
        "AccessTokenResponse[accessToken=<redacted>, expiresIn=$expiresIn, code=$code, message=<redacted>]"
}
