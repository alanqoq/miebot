package com.mieai.qqbot.admin.events

import java.time.Instant
import java.util.UUID

/** Detailed Outbox job view. Lease owner and fencing token are intentionally omitted. */
data class OutboxJobDetailResponse(
    val id: UUID,
    val environment: String,
    val botId: UUID,
    val botDisplayName: String?,
    val appId: String?,
    val sourceEventId: UUID?,
    val jobType: String,
    val dedupKey: String?,
    val status: String,
    val attempt: Long,
    val availableAt: Instant,
    val leaseUntil: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val completedAt: Instant?,
    val lastError: String?,
    val producerBindingId: UUID?,
    val platformMessageId: String?,
    val platformMessageSequence: Int?,
    val platformTimestamp: String?,
    val payload: String,
    val payloadTruncated: Boolean,
)
