package com.mieai.qqbot.persistence.outbox

import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Reliable Outbox storage and fenced worker state transitions. */
interface OutboxRepository {
    fun create(job: NewOutboxJob)

    fun findById(id: UUID): OutboxJob?

    fun findByIdAndProducerBindingId(id: UUID, producerBindingId: UUID): OutboxJob?

    /** Returns a bounded, newest-first page without changing processing state. */
    fun query(query: OutboxQuery): OutboxPage

    /** Returns a point-in-time count of jobs grouped by durable lifecycle status. */
    fun statistics(): OutboxQueueStats

    /** Atomically claims one available or lease-expired job and increments its fencing token. */
    fun claimNext(leaseOwner: String, now: Instant, leaseDuration: Duration): OutboxJob?

    /** Claims only work for a bot currently leased by [botLeaseOwner]. */
    fun claimNextOwned(
        leaseOwner: String,
        botLeaseOwner: String,
        now: Instant,
        leaseDuration: Duration,
    ): OutboxJob?

    fun markSucceeded(id: UUID, fencingToken: Long, now: Instant, receipt: OutboxSendReceipt)

    fun markRetry(id: UUID, fencingToken: Long, now: Instant, availableAt: Instant, error: String)

    fun markResultUnknown(id: UUID, fencingToken: Long, now: Instant, reason: String)

    fun markDeadLetter(id: UUID, fencingToken: Long, now: Instant, reason: String)
}
