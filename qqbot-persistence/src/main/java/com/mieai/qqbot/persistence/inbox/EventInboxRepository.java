package com.mieai.qqbot.persistence.inbox;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Reliable Inbox storage with platform-event deduplication. */
public interface EventInboxRepository {
    InboxInsertResult insertOrGet(IncomingEvent event);

    Optional<InboxEvent> findById(UUID id);

    /** Returns a bounded, newest-first page without changing processing state. */
    InboxPage query(InboxQuery query);

    /** Claims one event for asynchronous plugin delivery. Legacy test adapters may omit this. */
    default Optional<InboxEvent> claimNext(String leaseOwner, Instant now, Duration leaseDuration) {
        throw new UnsupportedOperationException("Inbox claiming is not implemented by this adapter");
    }

    /** Claims only events for a bot currently owned by {@code botLeaseOwner}. */
    default Optional<InboxEvent> claimNextOwned(
            String leaseOwner, String botLeaseOwner, Instant now, Duration leaseDuration) {
        return claimNext(leaseOwner, now, leaseDuration);
    }

    default void markDispatched(UUID id, long fencingToken, Instant now) {
        throw new UnsupportedOperationException("Inbox transitions are not implemented by this adapter");
    }

    default void markRetry(UUID id, long fencingToken, Instant now, Instant availableAt, String error) {
        throw new UnsupportedOperationException("Inbox transitions are not implemented by this adapter");
    }

    default void markDeadLetter(UUID id, long fencingToken, Instant now, String reason) {
        throw new UnsupportedOperationException("Inbox transitions are not implemented by this adapter");
    }
}
