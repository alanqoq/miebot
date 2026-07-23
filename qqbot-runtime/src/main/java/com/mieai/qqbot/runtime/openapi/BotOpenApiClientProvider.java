package com.mieai.qqbot.runtime.openapi;

import com.mieai.qqbot.client.QqOpenApiClient;
import com.mieai.qqbot.domain.bot.BotId;

/** Bot-scoped access to the official QQ OpenAPI client without exposing AppSecret values. */
public interface BotOpenApiClientProvider extends AutoCloseable {
    QqOpenApiClient clientFor(BotId botId);

    void invalidate(BotId botId);

    void invalidateAll();

    @Override
    void close();
}
