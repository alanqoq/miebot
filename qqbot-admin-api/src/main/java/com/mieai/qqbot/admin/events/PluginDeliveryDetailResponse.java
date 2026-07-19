package com.mieai.qqbot.admin.events;

import java.time.Instant;
import java.util.UUID;

/** Detailed plugin attempt view. Lease owner and fencing token are intentionally omitted. */
public record PluginDeliveryDetailResponse(
        UUID id,
        UUID eventId,
        UUID bindingId,
        String pluginId,
        UUID botId,
        String botDisplayName,
        String handlerId,
        String status,
        int attempt,
        Instant availableAt,
        Instant leaseUntil,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt,
        String lastError,
        String eventType,
        String platformEventId,
        Instant eventReceivedAt) {
}
