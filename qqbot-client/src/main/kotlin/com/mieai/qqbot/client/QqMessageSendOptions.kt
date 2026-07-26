package com.mieai.qqbot.client

import com.mieai.qqbot.protocol.openapi.QqMessageModels.MessageReference

/** Optional fields shared by QQ message sends. */
data class QqMessageSendOptions(
    val messageReference: MessageReference? = null,
    val wakeup: Boolean = false,
)
