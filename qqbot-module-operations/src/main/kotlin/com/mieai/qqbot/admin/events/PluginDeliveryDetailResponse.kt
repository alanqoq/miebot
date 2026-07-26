package com.mieai.qqbot.admin.events

import java.time.Instant
import java.util.UUID

/** Detailed plugin attempt view. Lease owner and fencing token are intentionally omitted. */
data class PluginDeliveryDetailResponse(
    val id: UUID,
    val eventId: UUID,
    val bindingId: UUID,
    val pluginId: String,
    val botId: UUID,
    val botDisplayName: String?,
    val handlerId: String,
    val status: String,
    val attempt: Int,
    val availableAt: Instant,
    val leaseUntil: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val completedAt: Instant?,
    val lastError: String?,
    val eventType: String,
    val platformEventId: String,
    val eventReceivedAt: Instant,
)
