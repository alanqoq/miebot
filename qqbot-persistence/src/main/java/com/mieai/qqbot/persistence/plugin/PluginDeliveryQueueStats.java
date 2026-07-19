package com.mieai.qqbot.persistence.plugin;

/** Point-in-time lifecycle counts for plugin deliveries. */
public record PluginDeliveryQueueStats(
        long totalCount,
        long pendingCount,
        long inProgressCount,
        long retryWaitCount,
        long succeededCount,
        long deadLetterCount,
        long pausedCount) {

    public PluginDeliveryQueueStats {
        if (totalCount < 0 || pendingCount < 0 || inProgressCount < 0 || retryWaitCount < 0
                || succeededCount < 0 || deadLetterCount < 0 || pausedCount < 0) {
            throw new IllegalArgumentException("plugin delivery counts must not be negative");
        }
        if (pendingCount + inProgressCount + retryWaitCount + succeededCount
                + deadLetterCount + pausedCount != totalCount) {
            throw new IllegalArgumentException("plugin delivery status counts must add up to totalCount");
        }
    }
}
