package com.mieai.qqbot.runtime.openapi

import com.mieai.qqbot.client.QqOpenApiClient
import com.mieai.qqbot.domain.bot.BotId

/** Bot-scoped access to the official QQ OpenAPI client without exposing AppSecret values. */
interface BotOpenApiClientProvider : AutoCloseable {
    fun clientFor(botId: BotId): QqOpenApiClient

    fun invalidate(botId: BotId)

    fun invalidateAll()

    override fun close()
}
