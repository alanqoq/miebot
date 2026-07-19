package com.mieai.qqbot.admin.events;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Cursor page returned by both the Outbox and DLQ administration endpoints. */
public record OutboxPageResponse(
        List<OutboxJobSummaryResponse> items,
        String nextCursor,
        boolean hasMore,
        Instant observedAt,
        OutboxQueueStatsResponse stats) {

    public OutboxPageResponse {
        items = List.copyOf(Objects.requireNonNull(items, "items must not be null"));
        Objects.requireNonNull(observedAt, "observedAt must not be null");
        Objects.requireNonNull(stats, "stats must not be null");
        if (!hasMore && nextCursor != null) {
            throw new IllegalArgumentException("nextCursor must be null when hasMore is false");
        }
        if (hasMore && (nextCursor == null || nextCursor.isBlank())) {
            throw new IllegalArgumentException("nextCursor is required when hasMore is true");
        }
    }
}
