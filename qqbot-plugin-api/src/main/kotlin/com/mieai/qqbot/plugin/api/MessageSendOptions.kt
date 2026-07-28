package com.mieai.qqbot.plugin.api

/** Optional transport metadata applied when an outbound message is enqueued. */
data class MessageSendOptions(
    val messageReference: MessageReference? = null,
)
