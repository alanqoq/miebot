package com.mieai.qqbot.admin.audit;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record AuditLogPageResponse(
        List<AuditLogResponse> items,
        String nextCursor,
        boolean hasMore,
        Instant observedAt) {
    public AuditLogPageResponse {
        items = List.copyOf(Objects.requireNonNull(items, "items must not be null"));
        Objects.requireNonNull(observedAt, "observedAt must not be null");
    }
}
