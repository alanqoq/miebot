package com.mieai.qqbot.runtime.outbox

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import com.mieai.qqbot.client.QqMessageTargetType

data class OutboundTextPayload @JsonCreator(mode = JsonCreator.Mode.PROPERTIES) constructor(
    @JsonProperty("targetType") val targetType: QqMessageTargetType,
    @JsonProperty("targetId") val targetId: String,
    @JsonProperty("content") val content: String,
    @JsonProperty("replyMessageId") val replyMessageId: String?,
    @JsonProperty("replyEventId") val replyEventId: String?,
    @JsonProperty("messageSequence") val messageSequence: Int,
) {
    companion object {
        const val JOB_TYPE = "QQ_SEND_TEXT"
    }
}
