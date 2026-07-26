package com.mieai.qqbot.persistence.inbox

import com.mieai.qqbot.domain.bot.BotEnvironment
import com.mieai.qqbot.domain.bot.BotId
import com.mieai.qqbot.persistence.internal.PersistenceValidation
import java.time.Instant
import java.util.UUID

/** Platform event ready to be persisted before any business processing begins. */
data class IncomingEvent(
    val id: UUID,
    val environment: BotEnvironment,
    val botId: BotId,
    val eventType: String,
    val platformEventId: String,
    val payload: String,
    val receivedAt: Instant,
) {
    init {
        PersistenceValidation.requireIdentifier(id, "id")
        PersistenceValidation.requireToken(eventType, "eventType", 128)
        PersistenceValidation.requireToken(platformEventId, "platformEventId", 255)
        PersistenceValidation.requirePayload(payload, "payload")
    }
}
