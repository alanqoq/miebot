package com.mieai.qqbot.protocol.auth

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/** Request body for obtaining an app access token. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class AccessTokenRequest(
    @JsonProperty("appId") val appId: String?,
    @JsonProperty("clientSecret") val clientSecret: String?,
) {
    override fun toString(): String = "AccessTokenRequest[appId=$appId, clientSecret=<redacted>]"
}
