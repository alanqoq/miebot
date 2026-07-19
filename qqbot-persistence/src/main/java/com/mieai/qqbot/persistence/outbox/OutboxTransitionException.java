package com.mieai.qqbot.persistence.outbox;

import static com.mieai.qqbot.persistence.internal.PersistenceValidation.requireIdentifier;

import java.util.Objects;
import java.util.UUID;

/** Raised when a stale or invalid worker attempts an Outbox state transition. */
public final class OutboxTransitionException extends RuntimeException {
    private final UUID jobId;
    private final long fencingToken;
    private final OutboxStatus targetStatus;

    public OutboxTransitionException(UUID jobId, long fencingToken, OutboxStatus targetStatus) {
        super("Outbox job " + requireIdentifier(jobId, "jobId")
                + " cannot transition to " + Objects.requireNonNull(targetStatus, "targetStatus must not be null")
                + " with fencing token " + fencingToken);
        if (fencingToken < 1L) {
            throw new IllegalArgumentException("fencingToken must be positive");
        }
        this.jobId = jobId;
        this.fencingToken = fencingToken;
        this.targetStatus = targetStatus;
    }

    public UUID jobId() {
        return jobId;
    }

    public long fencingToken() {
        return fencingToken;
    }

    public OutboxStatus targetStatus() {
        return targetStatus;
    }
}
