package com.mieai.qqbot.runtime.security;

import com.mieai.qqbot.domain.bot.BotEnvironment;
import com.mieai.qqbot.domain.bot.BotId;
import com.mieai.qqbot.domain.bot.QqAppId;
import java.util.Objects;

/** Configuration identity cryptographically bound to an encrypted AppSecret. */
public record AppSecretBinding(BotId botId, QqAppId appId, BotEnvironment environment) {
    public AppSecretBinding {
        Objects.requireNonNull(botId, "botId must not be null");
        Objects.requireNonNull(appId, "appId must not be null");
        Objects.requireNonNull(environment, "environment must not be null");
    }
}
