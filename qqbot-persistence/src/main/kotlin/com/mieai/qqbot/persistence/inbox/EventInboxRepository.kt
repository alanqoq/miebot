package com.mieai.qqbot.persistence.inbox

import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Reliable Inbox storage with platform-event deduplication. */
interface EventInboxRepository {
    fun insertOrGet(event: IncomingEvent): InboxInsertResult

    fun findById(id: UUID): InboxEvent?

    /** Returns a bounded, newest-first page without changing processing state. */
    fun query(query: InboxQuery): InboxPage

    /** Claims one event for asynchronous plugin delivery. */
    fun claimNext(leaseOwner: String, now: Instant, leaseDuration: Duration): InboxEvent?

    /** Claims only events for a bot currently owned by [botLeaseOwner]. */
    fun claimNextOwned(
        leaseOwner: String,
        botLeaseOwner: String,
        now: Instant,
        leaseDuration: Duration,
    ): InboxEvent?

    fun markDispatched(id: UUID, fencingToken: Long, now: Instant)

    fun markRetry(id: UUID, fencingToken: Long, now: Instant, availableAt: Instant, error: String)

    fun markDeadLetter(id: UUID, fencingToken: Long, now: Instant, reason: String)
}
