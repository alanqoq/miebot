package com.mieai.qqbot.protocol.gateway

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/** Data sent with an opcode 6 Resume command. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class GatewayResume(
    val token: String,
    @JsonProperty("session_id") val sessionId: String,
    @JsonProperty("seq") val sequence: Long,
) {
    override fun toString(): String = "GatewayResume[token=<redacted>, sessionId=$sessionId, sequence=$sequence]"
}
