package com.mieai.qqbot.admin.events;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record InboxPageResponse(
        List<InboxEventSummaryResponse> items,
        String nextCursor,
        boolean hasMore,
        Instant observedAt) {

    public InboxPageResponse {
        items = List.copyOf(Objects.requireNonNull(items, "items must not be null"));
        Objects.requireNonNull(observedAt, "observedAt must not be null");
        if (!hasMore && nextCursor != null) {
            throw new IllegalArgumentException("nextCursor must be null when hasMore is false");
        }
        if (hasMore && (nextCursor == null || nextCursor.isBlank())) {
            throw new IllegalArgumentException("nextCursor is required when hasMore is true");
        }
    }
}
