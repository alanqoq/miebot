package com.mieai.qqbot.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/** Minimal response fields; QQ may add fields without breaking the sender. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class QqMessageSendResult(
    val id: String?,
    @JsonProperty("msg_seq") val msgSeq: Int?,
    val timestamp: String?,
)
