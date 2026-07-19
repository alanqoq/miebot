package com.mieai.qqbot.persistence.plugin;

import static com.mieai.qqbot.persistence.internal.PersistenceValidation.requireIdentifier;
import static com.mieai.qqbot.persistence.internal.PersistenceValidation.requirePayload;
import static com.mieai.qqbot.persistence.internal.PersistenceValidation.requireToken;

import com.mieai.qqbot.domain.bot.BotId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** One independently configurable plugin instance attached to one bot. */
public record BotPluginBinding(
        UUID id,
        String pluginId,
        BotId botId,
        String configJson,
        boolean enabled,
        long revision,
        Instant createdAt,
        Instant updatedAt) {
    public BotPluginBinding {
        requireIdentifier(id, "id");
        requireToken(pluginId, "pluginId", 128);
        Objects.requireNonNull(botId, "botId must not be null");
        requirePayload(configJson, "configJson");
        if (revision < 0L) throw new IllegalArgumentException("revision must not be negative");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (updatedAt.isBefore(createdAt)) throw new IllegalArgumentException("updatedAt must not be before createdAt");
    }
}
