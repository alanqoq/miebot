package com.mieai.qqbot.plugin.api;

import com.mieai.qqbot.domain.bot.BotEnvironment;
import com.mieai.qqbot.domain.bot.BotId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Versioned, transport-independent event delivered to one plugin binding. */
public record PluginEvent(
        UUID id,
        BotId botId,
        BotEnvironment environment,
        String eventType,
        String platformEventId,
        String rawPayload,
        Instant receivedAt,
        Optional<InboundMessage> message) {
    public PluginEvent {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(botId, "botId must not be null");
        Objects.requireNonNull(environment, "environment must not be null");
        requireToken(eventType, "eventType", 128);
        requireToken(platformEventId, "platformEventId", 255);
        if (rawPayload == null || rawPayload.isBlank()) throw new IllegalArgumentException("rawPayload must not be blank");
        Objects.requireNonNull(receivedAt, "receivedAt must not be null");
        Objects.requireNonNull(message, "message must not be null");
    }

    private static void requireToken(String value, String name, int max) {
        if (value == null || value.isBlank() || !value.equals(value.strip())
                || value.codePoints().anyMatch(Character::isWhitespace)
                || value.codePoints().anyMatch(Character::isISOControl)
                || value.codePointCount(0, value.length()) > max) {
            throw new IllegalArgumentException(name + " is invalid");
        }
    }
}
