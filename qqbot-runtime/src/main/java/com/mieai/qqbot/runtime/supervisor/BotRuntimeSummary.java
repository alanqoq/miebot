package com.mieai.qqbot.runtime.supervisor;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Aggregate runtime counts used by health and dashboard endpoints. */
public record BotRuntimeSummary(
        int configuredBots,
        int enabledBots,
        int runningBots,
        int onlineBots,
        int reconnectingBots,
        int failedBots,
        Instant observedAt) {

    public BotRuntimeSummary {
        if (configuredBots < 0
                || enabledBots < 0
                || runningBots < 0
                || onlineBots < 0
                || reconnectingBots < 0
                || failedBots < 0) {
            throw new IllegalArgumentException("runtime counts must not be negative");
        }
        Objects.requireNonNull(observedAt, "observedAt must not be null");
    }

    static BotRuntimeSummary from(List<BotRuntimeStatus> statuses, Instant observedAt) {
        Objects.requireNonNull(statuses, "statuses must not be null");
        int enabled = 0;
        int running = 0;
        int online = 0;
        int reconnecting = 0;
        int failed = 0;
        for (BotRuntimeStatus status : statuses) {
            if (status.desiredEnabled()) {
                enabled++;
            }
            if (status.state().isRunning()) {
                running++;
            }
            if (status.state() == BotRuntimeState.ONLINE) {
                online++;
            }
            if (status.state() == BotRuntimeState.RECONNECTING) {
                reconnecting++;
            }
            if (status.state() == BotRuntimeState.FAILED) {
                failed++;
            }
        }
        return new BotRuntimeSummary(
                statuses.size(), enabled, running, online, reconnecting, failed, observedAt);
    }
}
