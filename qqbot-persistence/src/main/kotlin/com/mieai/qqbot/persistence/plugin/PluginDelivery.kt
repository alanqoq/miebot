package com.mieai.qqbot.persistence.plugin

import com.mieai.qqbot.persistence.internal.PersistenceValidation
import java.time.Instant
import java.util.UUID

data class PluginDelivery(
    val id: UUID,
    val eventId: UUID,
    val bindingId: UUID,
    val handlerId: String,
    val status: PluginDeliveryStatus,
    val attempt: Int,
    val availableAt: Instant,
    val leaseOwner: String?,
    val leaseUntil: Instant?,
    val fencingToken: Long,
    val lastError: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val completedAt: Instant?,
) {
    init {
        PersistenceValidation.requireIdentifier(id, "id")
        PersistenceValidation.requireIdentifier(eventId, "eventId")
        PersistenceValidation.requireIdentifier(bindingId, "bindingId")
        PersistenceValidation.requireToken(handlerId, "handlerId", 128)
        require(attempt >= 0) { "attempt must not be negative" }
        require(fencingToken >= 0L) { "fencingToken must not be negative" }
        leaseOwner?.let { PersistenceValidation.requireToken(it, "leaseOwner", 255) }
        lastError?.let { PersistenceValidation.requirePayload(it, "lastError") }
        require(status != PluginDeliveryStatus.IN_PROGRESS || (leaseOwner != null && leaseUntil != null)) {
            "in-progress delivery must have a lease"
        }
        require(status == PluginDeliveryStatus.IN_PROGRESS || (leaseOwner == null && leaseUntil == null)) {
            "non-running delivery must not have a lease"
        }
        require(status.terminal() == (completedAt != null)) { "terminal status and completedAt must be consistent" }
    }
}
