package com.mieai.qqbot.plugin.api

import com.mieai.qqbot.domain.bot.BotEnvironment
import com.mieai.qqbot.domain.bot.BotId
import java.time.Instant
import java.util.UUID

/** Versioned, transport-independent event delivered to one plugin binding. */
data class PluginEvent(
    val id: UUID,
    val botId: BotId,
    val environment: BotEnvironment,
    val eventType: String,
    val platformEventId: String,
    val rawPayload: String,
    val receivedAt: Instant,
    val message: InboundMessage?,
) {
    init {
        requireToken(eventType, "eventType", 128)
        requireToken(platformEventId, "platformEventId", 255)
        require(rawPayload.isNotBlank()) { "rawPayload must not be blank" }
    }

    private fun requireToken(value: String, name: String, max: Int) {
        require(value.isNotBlank() && value == value.trim()) { "$name is invalid" }
        require(value.codePoints().noneMatch { Character.isWhitespace(it) }) { "$name is invalid" }
        require(value.codePoints().noneMatch { Character.isISOControl(it) }) { "$name is invalid" }
        require(value.codePointCount(0, value.length) <= max) { "$name is invalid" }
    }
}
