package com.mieai.qqbot.admin.bot

import com.mieai.qqbot.client.QqMediaKind
import com.mieai.qqbot.client.QqMessageTargetType
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import java.util.UUID

data class SendBotMessageRequest(
    @field:NotNull val kind: BotMessageKind?,
    @field:NotNull val targetType: QqMessageTargetType?,
    @field:NotBlank val targetId: String?,
    val content: String?,
    val mediaKind: QqMediaKind?,
    val mediaUrl: String?,
    val mediaAssetId: UUID?,
    val payload: Map<String, Any>?,
    val replyMessageId: String?,
    val replyEventId: String?,
    @field:Positive val messageSequence: Int? = 1,
)
