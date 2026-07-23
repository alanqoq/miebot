package com.mieai.qqbot.admin.events;

import java.time.Instant;
import java.util.UUID;

/** Public, non-payload representation used by the Inbox list. */
public record InboxEventSummaryResponse(
        UUID id,
        String environment,
        UUID botId,
        String botDisplayName,
        String appId,
        String eventType,
        String platformEventId,
        String status,
        int attempt,
        Instant availableAt,
        Instant receivedAt,
        Instant updatedAt,
        String lastError) {
}
