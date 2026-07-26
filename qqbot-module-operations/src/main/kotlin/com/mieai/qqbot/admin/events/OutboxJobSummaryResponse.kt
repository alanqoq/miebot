package com.mieai.qqbot.admin.events

import java.time.Instant
import java.util.UUID

/** Public, non-payload representation used by Outbox and DLQ lists. */
data class OutboxJobSummaryResponse(
    val id: UUID,
    val environment: String,
    val botId: UUID,
    val botDisplayName: String?,
    val appId: String?,
    val sourceEventId: UUID?,
    val jobType: String,
    val status: String,
    val attempt: Long,
    val availableAt: Instant,
    val createdAt: Instant,
    val updatedAt: Instant,
    val completedAt: Instant?,
    val lastError: String?,
)
