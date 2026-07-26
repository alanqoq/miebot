package com.mieai.qqbot.admin.events

import java.time.Instant
import java.util.UUID

data class PluginDeliverySummaryResponse(
    val id: UUID,
    val eventId: UUID,
    val bindingId: UUID,
    val pluginId: String?,
    val botId: UUID?,
    val botDisplayName: String?,
    val handlerId: String,
    val status: String,
    val attempt: Int,
    val availableAt: Instant,
    val createdAt: Instant,
    val updatedAt: Instant,
    val completedAt: Instant?,
    val lastError: String?,
)
