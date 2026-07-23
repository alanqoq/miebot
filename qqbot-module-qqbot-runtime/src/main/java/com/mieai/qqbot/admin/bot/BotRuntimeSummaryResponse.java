package com.mieai.qqbot.admin.bot;

import com.mieai.qqbot.runtime.supervisor.BotRuntimeState;
import com.mieai.qqbot.runtime.supervisor.BotRuntimeStatus;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record BotRuntimeSummaryResponse(
        int totalCount,
        int enabledCount,
        int connectedCount,
        Instant observedAt,
        List<BotRuntimeStatusResponse> bots) {

    public BotRuntimeSummaryResponse {
        if (totalCount < 0 || enabledCount < 0 || connectedCount < 0) {
            throw new IllegalArgumentException("runtime counts must not be negative");
        }
        Objects.requireNonNull(observedAt, "observedAt must not be null");
        bots = List.copyOf(Objects.requireNonNull(bots, "bots must not be null"));
    }

    static BotRuntimeSummaryResponse from(List<BotRuntimeStatus> statuses, Instant observedAt) {
        Objects.requireNonNull(statuses, "statuses must not be null");
        List<BotRuntimeStatusResponse> bots = statuses.stream()
                .map(BotRuntimeStatusResponse::from)
                .toList();
        int enabledCount = (int) statuses.stream()
                .filter(BotRuntimeStatus::desiredEnabled)
                .count();
        int connectedCount = (int) statuses.stream()
                .filter(status -> status.state() == BotRuntimeState.ONLINE)
                .count();
        return new BotRuntimeSummaryResponse(
                statuses.size(), enabledCount, connectedCount, observedAt, bots);
    }
}
