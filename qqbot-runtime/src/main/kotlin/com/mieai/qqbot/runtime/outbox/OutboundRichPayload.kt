package com.mieai.qqbot.runtime.outbox

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import com.mieai.qqbot.client.QqMessageTargetType
import com.mieai.qqbot.client.QqRichMessageKind

data class OutboundRichPayload @JsonCreator(mode = JsonCreator.Mode.PROPERTIES) constructor(
    @JsonProperty("targetType") val targetType: QqMessageTargetType,
    @JsonProperty("targetId") val targetId: String,
    @JsonProperty("kind") val kind: QqRichMessageKind,
    @JsonProperty("payload") val payload: Map<String, Any>,
    @JsonProperty("replyMessageId") val replyMessageId: String?,
    @JsonProperty("replyEventId") val replyEventId: String?,
    @JsonProperty("messageSequence") val messageSequence: Int,
) {
    companion object {
        const val JOB_TYPE = "QQ_SEND_RICH"
    }
}
