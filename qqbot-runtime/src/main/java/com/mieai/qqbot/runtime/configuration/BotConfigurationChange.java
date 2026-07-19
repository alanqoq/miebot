package com.mieai.qqbot.runtime.configuration;

import com.mieai.qqbot.domain.bot.BotId;
import com.mieai.qqbot.domain.bot.BotRevision;
import java.util.Objects;

/** Notification emitted only after a bot configuration write has committed. */
public record BotConfigurationChange(
        BotId botId,
        BotRevision revision,
        boolean enabled,
        BotConfigurationChangeKind kind) {

    public BotConfigurationChange {
        Objects.requireNonNull(botId, "botId must not be null");
        Objects.requireNonNull(revision, "revision must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
        if (enabled && kind == BotConfigurationChangeKind.DISABLED) {
            throw new IllegalArgumentException("a disabled change must not be enabled");
        }
        if (!enabled && kind == BotConfigurationChangeKind.ENABLED) {
            throw new IllegalArgumentException("an enabled change must be enabled");
        }
    }
}
