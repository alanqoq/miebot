package com.mieai.qqbot.admin.events

import java.time.Instant
import java.util.UUID

/** Detailed Inbox event view. The raw payload is deliberately kept as text. */
data class InboxEventDetailResponse(
    val id: UUID,
    val environment: String,
    val botId: UUID,
    val botDisplayName: String?,
    val appId: String?,
    val eventType: String,
    val platformEventId: String,
    val status: String,
    val attempt: Int,
    val availableAt: Instant,
    val receivedAt: Instant,
    val updatedAt: Instant,
    val lastError: String?,
    val payload: String,
    val payloadTruncated: Boolean,
)
