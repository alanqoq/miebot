package com.mieai.qqbot.protocol.gateway

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/** Framework-neutral representation of a Gateway payload. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class GatewayEnvelope<T>(
    @JsonProperty("op") val op: Int,
    @JsonProperty("d") val data: T?,
    @JsonProperty("s") val sequence: Long?,
    @JsonProperty("t") val eventType: String?,
    @JsonProperty("id") val eventId: String? = null,
) {
    fun opcode(): GatewayOpcode = GatewayOpcode.fromCode(op)
}
