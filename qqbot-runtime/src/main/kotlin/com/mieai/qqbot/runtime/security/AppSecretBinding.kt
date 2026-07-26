package com.mieai.qqbot.runtime.security
import com.mieai.qqbot.domain.bot.*
data class AppSecretBinding(val botId: BotId, val appId: QqAppId, val environment: BotEnvironment)
