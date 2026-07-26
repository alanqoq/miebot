package com.mieai.qqbot.client

import com.mieai.qqbot.domain.bot.QqAppId

/** Credentials used only by the core token client. */
data class BotCredentials(val appId: QqAppId, val appSecret: AppSecret) : AutoCloseable {
    override fun toString(): String = "BotCredentials[appId=$appId, appSecret=<redacted>]"

    override fun close() {
        appSecret.close()
    }
}
