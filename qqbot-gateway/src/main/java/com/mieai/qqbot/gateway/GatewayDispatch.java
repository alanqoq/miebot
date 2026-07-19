package com.mieai.qqbot.gateway;

import com.mieai.qqbot.protocol.gateway.GatewayEnvelope;
import com.mieai.qqbot.protocol.json.JsonCodecs;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Raw dispatch offered to the durable-ingress hook before the session advances its sequence. */
public record GatewayDispatch(
        long sequence,
        String eventType,
        String rawPayload,
        String platformEventId,
        String sessionId) {
    public GatewayDispatch {
        if (sequence < 0L) {
            throw new IllegalArgumentException("sequence must not be negative");
        }
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(rawPayload, "rawPayload must not be null");
        if (platformEventId != null && platformEventId.isBlank()) {
            platformEventId = null;
        }
        if (platformEventId == null) {
            platformEventId = extractPlatformEventId(eventType, rawPayload);
        }
        if (sessionId != null && sessionId.isBlank()) {
            sessionId = null;
        }
        if (sessionId != null && sessionId.codePoints().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("sessionId must not contain whitespace");
        }
    }

    public GatewayDispatch(long sequence, String eventType, String rawPayload, String platformEventId) {
        this(sequence, eventType, rawPayload, platformEventId, null);
    }

    /** Source-compatible constructor that extracts the event id from the raw Gateway envelope. */
    public GatewayDispatch(long sequence, String eventType, String rawPayload) {
        this(sequence, eventType, rawPayload, extractPlatformEventId(eventType, rawPayload), null);
    }

    private static String extractPlatformEventId(String eventType, String rawPayload) {
        try {
            GatewayEnvelope<Object> envelope = JsonCodecs.defaultCodec()
                    .decodeGatewayEnvelope(rawPayload, Object.class);
            String topLevel = normalized(envelope.eventId());
            if (topLevel != null) {
                return topLevel;
            }
            if (envelope.data() instanceof Map<?, ?> data) {
                // Explicit event_id fields are event-scoped across event families. A bare d.id is
                // only event-scoped for message/interaction/reaction events; for resource updates
                // (for example GUILD_UPDATE) it is usually the resource ID and must not collapse
                // later updates into one Inbox row.
                for (String key : new String[] {"event_id", "eventId"}) {
                    String nested = normalized(data.get(key));
                    if (nested != null) {
                        return nested;
                    }
                }
                if (messageLike(eventType)) {
                    for (String key : new String[] {"id", "msg_id", "message_id"}) {
                        String nested = normalized(data.get(key));
                        if (nested != null) {
                            return nested;
                        }
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // GatewaySession has already validated the frame; compatibility constructors should
            // remain usable for opaque test/future payloads even when extraction is unavailable.
        }
        return null;
    }

    private static boolean messageLike(String eventType) {
        String normalized = eventType.toUpperCase(Locale.ROOT);
        return normalized.contains("MESSAGE")
                || normalized.contains("INTERACTION")
                || normalized.contains("REACTION");
    }

    private static String normalized(Object value) {
        if (value == null) {
            return null;
        }
        String candidate = value.toString().strip();
        if (candidate.isEmpty()
                || candidate.codePoints().anyMatch(Character::isISOControl)
                || candidate.codePoints().anyMatch(Character::isWhitespace)) {
            return null;
        }
        return candidate;
    }
}
