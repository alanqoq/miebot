package com.mieai.qqbot.client;

import com.mieai.qqbot.domain.bot.QqAppId;
import java.util.Objects;

/** Credentials used only by the core token client. */
public record BotCredentials(QqAppId appId, AppSecret appSecret) implements AutoCloseable {
    public BotCredentials {
        Objects.requireNonNull(appId, "appId must not be null");
        Objects.requireNonNull(appSecret, "appSecret must not be null");
    }

    @Override
    public String toString() {
        return "BotCredentials[appId=" + appId + ", appSecret=<redacted>]";
    }

    @Override
    public void close() {
        appSecret.close();
    }
}
