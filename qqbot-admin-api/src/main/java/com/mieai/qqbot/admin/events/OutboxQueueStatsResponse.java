package com.mieai.qqbot.admin.events;

import com.mieai.qqbot.persistence.outbox.OutboxQueueStats;

/** Queue counters safe for the administrator UI; no lease or payload data is included. */
public record OutboxQueueStatsResponse(
        long totalCount,
        long pendingCount,
        long inProgressCount,
        long retryWaitCount,
        long succeededCount,
        long resultUnknownCount,
        long deadLetterCount) {

    public static OutboxQueueStatsResponse from(OutboxQueueStats stats) {
        return new OutboxQueueStatsResponse(
                stats.totalCount(),
                stats.pendingCount(),
                stats.inProgressCount(),
                stats.retryWaitCount(),
                stats.succeededCount(),
                stats.resultUnknownCount(),
                stats.deadLetterCount());
    }
}
