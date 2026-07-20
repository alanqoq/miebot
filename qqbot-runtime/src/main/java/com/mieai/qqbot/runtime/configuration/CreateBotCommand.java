package com.mieai.qqbot.runtime.configuration;

import com.mieai.qqbot.domain.bot.BotEnvironment;
import com.mieai.qqbot.domain.bot.GatewayIntents;
import com.mieai.qqbot.domain.bot.QqAppId;
import com.mieai.qqbot.domain.bot.ShardSpec;
import com.mieai.qqbot.runtime.security.AppSecret;
import java.util.Objects;

/** Complete input needed to create a configured bot. */
public record CreateBotCommand(
        String displayName,
        QqAppId appId,
        BotEnvironment environment,
        GatewayIntents intents,
        ShardSpec shardSpec,
        boolean enabled,
        AppSecret appSecret,
        long maxMediaUploadBytes) {

    public CreateBotCommand(String displayName, QqAppId appId, BotEnvironment environment,
            GatewayIntents intents, ShardSpec shardSpec, boolean enabled, AppSecret appSecret) {
        this(displayName, appId, environment, intents, shardSpec, enabled, appSecret,
                com.mieai.qqbot.domain.bot.BotDefinition.DEFAULT_MAX_MEDIA_UPLOAD_BYTES);
    }

    public CreateBotCommand {
        displayName = ConfigurationValidation.displayName(displayName);
        Objects.requireNonNull(appId, "appId must not be null");
        Objects.requireNonNull(environment, "environment must not be null");
        Objects.requireNonNull(intents, "intents must not be null");
        Objects.requireNonNull(shardSpec, "shardSpec must not be null");
        Objects.requireNonNull(appSecret, "appSecret must not be null");
        if (maxMediaUploadBytes < com.mieai.qqbot.domain.bot.BotDefinition.MIN_MAX_MEDIA_UPLOAD_BYTES
                || maxMediaUploadBytes > com.mieai.qqbot.domain.bot.BotDefinition.MAX_MAX_MEDIA_UPLOAD_BYTES) {
            throw new IllegalArgumentException("maxMediaUploadBytes must be between 1 MiB and 256 MiB");
        }
    }
}
