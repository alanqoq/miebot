package com.mieai.qqbot.runtime.configuration;

import com.mieai.qqbot.domain.bot.BotEnvironment;
import com.mieai.qqbot.domain.bot.BotId;
import com.mieai.qqbot.domain.bot.BotRevision;
import com.mieai.qqbot.domain.bot.GatewayIntents;
import com.mieai.qqbot.domain.bot.QqAppId;
import com.mieai.qqbot.domain.bot.ShardSpec;
import java.time.Instant;
import java.util.Objects;

/** Read model that deliberately exposes only whether an AppSecret is configured. */
public record BotConfigurationView(
        BotId id,
        String displayName,
        QqAppId appId,
        BotEnvironment environment,
        GatewayIntents intents,
        ShardSpec shardSpec,
        boolean enabled,
        BotRevision revision,
        Instant createdAt,
        Instant updatedAt,
        boolean secretConfigured) {

    public BotConfigurationView {
        Objects.requireNonNull(id, "id must not be null");
        displayName = ConfigurationValidation.displayName(displayName);
        Objects.requireNonNull(appId, "appId must not be null");
        Objects.requireNonNull(environment, "environment must not be null");
        Objects.requireNonNull(intents, "intents must not be null");
        Objects.requireNonNull(shardSpec, "shardSpec must not be null");
        Objects.requireNonNull(revision, "revision must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }
}
