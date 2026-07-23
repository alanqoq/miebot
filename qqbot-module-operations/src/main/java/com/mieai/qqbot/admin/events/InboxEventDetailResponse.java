package com.mieai.qqbot.admin.events;

import java.time.Instant;
import java.util.UUID;

/** Detailed Inbox event view. The raw payload is deliberately kept as text. */
public record InboxEventDetailResponse(
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
        String lastError,
        String payload,
        boolean payloadTruncated) {
}
