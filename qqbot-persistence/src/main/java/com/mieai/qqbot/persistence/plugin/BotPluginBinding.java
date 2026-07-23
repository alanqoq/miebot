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
        boolean enabled,
        long revision,
        Instant createdAt,
        Instant updatedAt,
        PluginBindingRuntimeState runtimeState,
        java.util.Optional<String> runtimeError) {

    public BotPluginBinding(UUID id, String pluginId, BotId botId,
            boolean enabled, long revision, Instant createdAt, Instant updatedAt) {
        this(id, pluginId, botId, enabled, revision, createdAt, updatedAt,
                enabled ? PluginBindingRuntimeState.ACTIVE : PluginBindingRuntimeState.PAUSED,
                java.util.Optional.empty());
    }
    public BotPluginBinding {
        requireIdentifier(id, "id");
        requireToken(pluginId, "pluginId", 128);
        Objects.requireNonNull(botId, "botId must not be null");
        if (revision < 0L) throw new IllegalArgumentException("revision must not be negative");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (updatedAt.isBefore(createdAt)) throw new IllegalArgumentException("updatedAt must not be before createdAt");
        Objects.requireNonNull(runtimeState, "runtimeState must not be null");
        Objects.requireNonNull(runtimeError, "runtimeError must not be null");
        runtimeError.ifPresent(value -> requirePayload(value, "runtimeError"));
    }
}
