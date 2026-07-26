package com.mieai.qqbot.plugin.api

import java.util.UUID

data class StagedMediaMessage(
    val target: MessageTarget,
    val media: StagedMedia,
    val content: String? = null,
    val replyMessageId: String? = null,
    val replyEventId: String? = null,
    val messageSequence: Int = 1,
    val deduplicationKey: String? = null,
    val sourceEventId: UUID? = null,
) {
    init {
        require(messageSequence >= 1) { "messageSequence must be positive" }
    }
}
