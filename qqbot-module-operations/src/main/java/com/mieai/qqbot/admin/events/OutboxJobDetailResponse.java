package com.mieai.qqbot.admin.events;

import java.time.Instant;
import java.util.UUID;

/** Detailed Outbox job view. Lease owner and fencing token are intentionally omitted. */
public record OutboxJobDetailResponse(
        UUID id,
        String environment,
        UUID botId,
        String botDisplayName,
        String appId,
        UUID sourceEventId,
        String jobType,
        String dedupKey,
        String status,
        long attempt,
        Instant availableAt,
        Instant leaseUntil,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt,
        String lastError,
        String payload,
        boolean payloadTruncated) {
}
