package com.mieai.qqbot.runtime.configuration

import com.mieai.qqbot.domain.bot.BotDefinition
import com.mieai.qqbot.domain.bot.BotEnvironment
import com.mieai.qqbot.domain.bot.BotId
import com.mieai.qqbot.domain.bot.BotRevision
import com.mieai.qqbot.domain.bot.GatewayIntents
import com.mieai.qqbot.domain.bot.QqAppId
import com.mieai.qqbot.domain.bot.ShardSpec
import com.mieai.qqbot.runtime.security.AppSecret

data class UpdateBotCommand(
    val botId: BotId,
    val expectedRevision: BotRevision,
    val displayName: String,
    val appId: QqAppId,
    val environment: BotEnvironment,
    val intents: GatewayIntents,
    val shardSpec: ShardSpec,
    val appSecret: AppSecret?,
    val maxMediaUploadBytes: Long? = null,
) {
    init {
        ConfigurationValidation.displayName(displayName)
        require(
            maxMediaUploadBytes == null ||
                maxMediaUploadBytes in BotDefinition.MIN_MAX_MEDIA_UPLOAD_BYTES..BotDefinition.MAX_MAX_MEDIA_UPLOAD_BYTES,
        ) {
            "maxMediaUploadBytes must be between 1 MiB and 256 MiB"
        }
    }
}
