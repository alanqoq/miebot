package com.mieai.qqbot.plugin.api

/** Stable message fields extracted from the QQ Gateway payload. */
data class InboundMessage(
    val replyTarget: MessageTarget,
    val messageId: String?,
    val eventId: String?,
    val authorId: String?,
    val content: String?,
    val referencedMessageId: String? = null,
)
