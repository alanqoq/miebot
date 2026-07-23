package com.mieai.qqbot.admin.events;

import java.time.Instant;
import java.util.UUID;

/** Public, non-payload representation used by Outbox and DLQ lists. */
public record OutboxJobSummaryResponse(
        UUID id,
        String environment,
        UUID botId,
        String botDisplayName,
        String appId,
        UUID sourceEventId,
        String jobType,
        String status,
        long attempt,
        Instant availableAt,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt,
        String lastError) {
}
