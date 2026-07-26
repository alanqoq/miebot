package com.mieai.qqbot.persistence.outbox

import com.mieai.qqbot.persistence.internal.PersistenceValidation
import java.util.UUID

/** Raised when a stale or invalid worker attempts an Outbox state transition. */
class OutboxTransitionException(
    val jobId: UUID,
    val fencingToken: Long,
    val targetStatus: OutboxStatus,
) : RuntimeException(
    "Outbox job ${PersistenceValidation.requireIdentifier(jobId, "jobId")} cannot transition to " +
        "$targetStatus with fencing token $fencingToken",
) {
    init {
        require(fencingToken >= 1L) { "fencingToken must be positive" }
    }

}
