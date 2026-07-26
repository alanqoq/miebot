package com.mieai.qqbot.persistence.inbox

import com.mieai.qqbot.domain.bot.BotEnvironment
import com.mieai.qqbot.domain.bot.BotId
import com.mieai.qqbot.persistence.internal.PersistenceValidation
import java.time.Instant
import java.util.UUID

/** Durable representation of a platform event and its reserved processing lease fields. */
data class InboxEvent(
    val id: UUID,
    val environment: BotEnvironment,
    val botId: BotId,
    val eventType: String,
    val platformEventId: String,
    val payload: String,
    val status: InboxStatus,
    val attempt: Int,
    val availableAt: Instant,
    val leaseOwner: String?,
    val leaseUntil: Instant?,
    val fencingToken: Long,
    val lastError: String?,
    val receivedAt: Instant,
    val updatedAt: Instant,
) {
    init {
        PersistenceValidation.requireIdentifier(id, "id")
        PersistenceValidation.requireToken(eventType, "eventType", 128)
        PersistenceValidation.requireToken(platformEventId, "platformEventId", 255)
        PersistenceValidation.requirePayload(payload, "payload")
        require(attempt >= 0) { "attempt must not be negative" }
        require(fencingToken >= 0L) { "fencingToken must not be negative" }

        leaseOwner?.let { PersistenceValidation.requireToken(it, "leaseOwner") }
        lastError?.let { PersistenceValidation.requirePayload(it, "lastError") }
        val processing = status == InboxStatus.PROCESSING
        val hasOwner = leaseOwner != null
        val hasDeadline = leaseUntil != null
        require(!((processing && (!hasOwner || !hasDeadline)) || (!processing && (hasOwner || hasDeadline)))) {
            "processing status and lease fields must be consistent"
        }
        require(!updatedAt.isBefore(receivedAt)) { "updatedAt must not be before receivedAt" }
    }
}
