package com.mieai.qqbot.persistence.outbox;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Reliable Outbox storage and fenced worker state transitions.
 *
 * <p>Claims move {@link OutboxStatus#PENDING PENDING} or {@link OutboxStatus#RETRY_WAIT
 * RETRY_WAIT} to {@link OutboxStatus#IN_PROGRESS IN_PROGRESS}. An expired in-progress lease may
 * be reclaimed, which increments both attempt and fencing token. A currently leased job can move
 * to {@code SUCCEEDED}, {@code RETRY_WAIT}, {@code RESULT_UNKNOWN}, or {@code DEAD_LETTER}.
 * Terminal jobs are never claimed automatically.
 */
public interface OutboxRepository {
    void create(NewOutboxJob job);

    Optional<OutboxJob> findById(UUID id);

    /** Returns a bounded, newest-first page without changing processing state. */
    OutboxPage query(OutboxQuery query);

    /** Returns a point-in-time count of jobs grouped by durable lifecycle status. */
    OutboxQueueStats statistics();

    /** Atomically claims one available or lease-expired job and increments its fencing token. */
    Optional<OutboxJob> claimNext(String leaseOwner, Instant now, Duration leaseDuration);

    /** Claims only work for a bot currently leased by {@code botLeaseOwner}. */
    default Optional<OutboxJob> claimNextOwned(
            String leaseOwner, String botLeaseOwner, Instant now, Duration leaseDuration) {
        return claimNext(leaseOwner, now, leaseDuration);
    }

    void markSucceeded(UUID id, long fencingToken, Instant now);

    void markRetry(UUID id, long fencingToken, Instant now, Instant availableAt, String error);

    void markResultUnknown(UUID id, long fencingToken, Instant now, String reason);

    void markDeadLetter(UUID id, long fencingToken, Instant now, String reason);
}
