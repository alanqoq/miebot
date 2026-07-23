package com.mieai.qqbot.admin.events;

import com.mieai.qqbot.persistence.plugin.PluginDeliveryQueueStats;

public record PluginDeliveryQueueStatsResponse(
        long totalCount,
        long pendingCount,
        long inProgressCount,
        long retryWaitCount,
        long succeededCount,
        long deadLetterCount,
        long pausedCount) {

    static PluginDeliveryQueueStatsResponse from(PluginDeliveryQueueStats stats) {
        return new PluginDeliveryQueueStatsResponse(
                stats.totalCount(), stats.pendingCount(), stats.inProgressCount(),
                stats.retryWaitCount(), stats.succeededCount(), stats.deadLetterCount(),
                stats.pausedCount());
    }
}
