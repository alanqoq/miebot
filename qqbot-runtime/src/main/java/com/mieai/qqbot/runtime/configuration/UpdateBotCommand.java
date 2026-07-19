package com.mieai.qqbot.runtime.configuration;

import com.mieai.qqbot.domain.bot.BotEnvironment;
import com.mieai.qqbot.domain.bot.BotId;
import com.mieai.qqbot.domain.bot.BotRevision;
import com.mieai.qqbot.domain.bot.GatewayIntents;
import com.mieai.qqbot.domain.bot.QqAppId;
import com.mieai.qqbot.domain.bot.ShardSpec;
import com.mieai.qqbot.runtime.security.AppSecret;
import java.util.Objects;
import java.util.Optional;

/** Editable bot fields plus the revision expected by the caller. */
public record UpdateBotCommand(
        BotId botId,
        BotRevision expectedRevision,
        String displayName,
        QqAppId appId,
        BotEnvironment environment,
        GatewayIntents intents,
        ShardSpec shardSpec,
        Optional<AppSecret> appSecret) {

    public UpdateBotCommand {
        Objects.requireNonNull(botId, "botId must not be null");
        Objects.requireNonNull(expectedRevision, "expectedRevision must not be null");
        displayName = ConfigurationValidation.displayName(displayName);
        Objects.requireNonNull(appId, "appId must not be null");
        Objects.requireNonNull(environment, "environment must not be null");
        Objects.requireNonNull(intents, "intents must not be null");
        Objects.requireNonNull(shardSpec, "shardSpec must not be null");
        Objects.requireNonNull(appSecret, "appSecret must not be null");
    }
}
