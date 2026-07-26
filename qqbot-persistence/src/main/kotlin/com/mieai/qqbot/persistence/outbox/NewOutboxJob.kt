package com.mieai.qqbot.persistence.outbox

import com.mieai.qqbot.domain.bot.BotEnvironment
import com.mieai.qqbot.domain.bot.BotId
import com.mieai.qqbot.persistence.internal.PersistenceValidation
import java.time.Instant
import java.util.UUID

/** New Outbox job before its first processing attempt. */
data class NewOutboxJob(
    val id: UUID,
    val environment: BotEnvironment,
    val botId: BotId,
    val sourceEventId: UUID?,
    val jobType: String,
    val dedupKey: String?,
    val payload: String,
    val availableAt: Instant,
    val createdAt: Instant,
    val producerBindingId: UUID?,
) {
    init {
        PersistenceValidation.requireIdentifier(id, "id")
        sourceEventId?.let { PersistenceValidation.requireIdentifier(it, "sourceEventId") }
        PersistenceValidation.requireToken(jobType, "jobType")
        dedupKey?.let { PersistenceValidation.requireToken(it, "dedupKey") }
        PersistenceValidation.requirePayload(payload, "payload")
        producerBindingId?.let { PersistenceValidation.requireIdentifier(it, "producerBindingId") }
        require(!availableAt.isBefore(createdAt)) { "availableAt must not be before createdAt" }
    }
}
