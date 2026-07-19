package com.mieai.qqbot.persistence.outbox;

/** Point-in-time counts used by operations views; no job payload is included. */
public record OutboxQueueStats(
        long totalCount,
        long pendingCount,
        long inProgressCount,
        long retryWaitCount,
        long succeededCount,
        long resultUnknownCount,
        long deadLetterCount) {

    public OutboxQueueStats {
        if (totalCount < 0
                || pendingCount < 0
                || inProgressCount < 0
                || retryWaitCount < 0
                || succeededCount < 0
                || resultUnknownCount < 0
                || deadLetterCount < 0) {
            throw new IllegalArgumentException("Outbox counts must not be negative");
        }
        long sum = pendingCount
                + inProgressCount
                + retryWaitCount
                + succeededCount
                + resultUnknownCount
                + deadLetterCount;
        if (sum != totalCount) {
            throw new IllegalArgumentException("Outbox status counts must add up to totalCount");
        }
    }
}
