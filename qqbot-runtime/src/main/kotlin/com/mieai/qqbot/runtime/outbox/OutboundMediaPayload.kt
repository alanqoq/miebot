package com.mieai.qqbot.runtime.outbox

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import com.mieai.qqbot.client.QqMediaKind
import com.mieai.qqbot.client.QqMessageTargetType
import java.util.UUID

/** Durable media-message contract consumed by the production Outbox worker. */
data class OutboundMediaPayload @JsonCreator(mode = JsonCreator.Mode.PROPERTIES) constructor(
    @JsonProperty("targetType") val targetType: QqMessageTargetType,
    @JsonProperty("targetId") val targetId: String,
    @JsonProperty("mediaKind") val mediaKind: QqMediaKind,
    @JsonProperty("mediaUrl") val mediaUrl: String,
    @JsonProperty("content") val content: String?,
    @JsonProperty("replyMessageId") val replyMessageId: String?,
    @JsonProperty("replyEventId") val replyEventId: String?,
    @JsonProperty("messageSequence") val messageSequence: Int,
    @JsonProperty("mediaAssetId") val mediaAssetId: UUID? = null,
) {
    companion object {
        const val JOB_TYPE = "QQ_SEND_MEDIA"
    }
}
