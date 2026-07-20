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
        Optional<AppSecret> appSecret,
        Long maxMediaUploadBytes) {

    public UpdateBotCommand(BotId botId, BotRevision expectedRevision, String displayName,
            QqAppId appId, BotEnvironment environment, GatewayIntents intents, ShardSpec shardSpec,
            Optional<AppSecret> appSecret) {
        this(botId, expectedRevision, displayName, appId, environment, intents, shardSpec, appSecret,
                null);
    }

    public UpdateBotCommand {
        Objects.requireNonNull(botId, "botId must not be null");
        Objects.requireNonNull(expectedRevision, "expectedRevision must not be null");
        displayName = ConfigurationValidation.displayName(displayName);
        Objects.requireNonNull(appId, "appId must not be null");
        Objects.requireNonNull(environment, "environment must not be null");
        Objects.requireNonNull(intents, "intents must not be null");
        Objects.requireNonNull(shardSpec, "shardSpec must not be null");
        Objects.requireNonNull(appSecret, "appSecret must not be null");
        if (maxMediaUploadBytes != null
                && (maxMediaUploadBytes < com.mieai.qqbot.domain.bot.BotDefinition.MIN_MAX_MEDIA_UPLOAD_BYTES
                || maxMediaUploadBytes > com.mieai.qqbot.domain.bot.BotDefinition.MAX_MAX_MEDIA_UPLOAD_BYTES)) {
            throw new IllegalArgumentException("maxMediaUploadBytes must be between 1 MiB and 256 MiB");
        }
    }
}
