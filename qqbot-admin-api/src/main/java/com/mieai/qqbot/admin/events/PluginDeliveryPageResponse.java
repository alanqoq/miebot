package com.mieai.qqbot.admin.events;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record PluginDeliveryPageResponse(
        List<PluginDeliverySummaryResponse> items,
        String nextCursor,
        boolean hasMore,
        Instant observedAt,
        PluginDeliveryQueueStatsResponse stats) {

    public PluginDeliveryPageResponse {
        items = List.copyOf(Objects.requireNonNull(items, "items must not be null"));
        Objects.requireNonNull(observedAt, "observedAt must not be null");
        Objects.requireNonNull(stats, "stats must not be null");
    }
}
