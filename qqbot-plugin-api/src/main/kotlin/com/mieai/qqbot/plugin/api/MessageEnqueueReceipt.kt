package com.mieai.qqbot.plugin.api

import java.time.Instant
import java.util.UUID

data class MessageEnqueueReceipt(
    val jobId: UUID,
    val alreadyPresent: Boolean,
    val queuedAt: Instant,
)
