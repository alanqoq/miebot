package com.mieai.qqbot.persistence.plugin

import com.mieai.qqbot.domain.bot.BotId
import com.mieai.qqbot.persistence.internal.PersistenceValidation
import java.time.Instant
import java.util.UUID

/** One independently configurable plugin instance attached to one bot. */
data class BotPluginBinding(
    val id: UUID,
    val pluginId: String,
    val botId: BotId,
    val enabled: Boolean,
    val revision: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
    val runtimeState: PluginBindingRuntimeState,
    val runtimeError: String?,
) {
    init {
        PersistenceValidation.requireIdentifier(id, "id")
        PersistenceValidation.requireToken(pluginId, "pluginId", 128)
        require(revision >= 0L) { "revision must not be negative" }
        require(!updatedAt.isBefore(createdAt)) { "updatedAt must not be before createdAt" }
        runtimeError?.let { PersistenceValidation.requirePayload(it, "runtimeError") }
    }
}
