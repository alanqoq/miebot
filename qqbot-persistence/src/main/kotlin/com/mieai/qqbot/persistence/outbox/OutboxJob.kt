package com.mieai.qqbot.persistence.outbox

import com.mieai.qqbot.domain.bot.BotEnvironment
import com.mieai.qqbot.domain.bot.BotId
import com.mieai.qqbot.persistence.internal.PersistenceValidation
import java.time.Instant
import java.util.UUID

/** Durable Outbox job including attempt count, lease, fencing state, and QQ delivery receipt. */
data class OutboxJob(
    val id: UUID,
    val environment: BotEnvironment,
    val botId: BotId,
    val sourceEventId: UUID?,
    val jobType: String,
    val dedupKey: String?,
    val payload: String,
    val status: OutboxStatus,
    val attempt: Long,
    val availableAt: Instant,
    val leaseOwner: String?,
    val leaseUntil: Instant?,
    val fencingToken: Long,
    val lastError: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val completedAt: Instant?,
    val producerBindingId: UUID?,
    val platformMessageId: String?,
    val platformMessageSequence: Int?,
    val platformTimestamp: String?,
) {
    init {
        PersistenceValidation.requireIdentifier(id, "id")
        sourceEventId?.let { PersistenceValidation.requireIdentifier(it, "sourceEventId") }
        PersistenceValidation.requireToken(jobType, "jobType")
        dedupKey?.let { PersistenceValidation.requireToken(it, "dedupKey") }
        PersistenceValidation.requirePayload(payload, "payload")
        require(attempt >= 0L) { "attempt must not be negative" }
        require(fencingToken >= 0L) { "fencingToken must not be negative" }
        leaseOwner?.let { PersistenceValidation.requireToken(it, "leaseOwner") }
        lastError?.let { PersistenceValidation.requirePayload(it, "lastError") }
        producerBindingId?.let { PersistenceValidation.requireIdentifier(it, "producerBindingId") }
        platformMessageId?.let { PersistenceValidation.requireToken(it, "platformMessageId", 255) }
        platformMessageSequence?.let { require(it >= 1) { "platformMessageSequence must be positive" } }
        platformTimestamp?.let { PersistenceValidation.requireToken(it, "platformTimestamp", 128) }

        val hasOwner = leaseOwner != null
        val hasDeadline = leaseUntil != null
        if (status == OutboxStatus.IN_PROGRESS) {
            require(hasOwner && hasDeadline) { "in-progress job must have a complete lease" }
        } else {
            require(!hasOwner && !hasDeadline) { "job outside in-progress state must not have a lease" }
        }
        require(status.isTerminal() == (completedAt != null)) { "terminal status and completedAt must be consistent" }
        require(!updatedAt.isBefore(createdAt)) { "updatedAt must not be before createdAt" }
        require(status == OutboxStatus.SUCCEEDED ||
            (platformMessageId == null && platformMessageSequence == null && platformTimestamp == null)) {
            "only succeeded jobs may contain a platform delivery receipt"
        }
    }
}
