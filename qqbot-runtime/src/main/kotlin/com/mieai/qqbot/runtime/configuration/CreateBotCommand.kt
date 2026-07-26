package com.mieai.qqbot.runtime.configuration

import com.mieai.qqbot.domain.bot.*
import com.mieai.qqbot.runtime.security.AppSecret

data class CreateBotCommand(val displayName: String, val appId: QqAppId, val environment: BotEnvironment, val intents: GatewayIntents, val shardSpec: ShardSpec, val enabled: Boolean, val appSecret: AppSecret, val maxMediaUploadBytes: Long = BotDefinition.DEFAULT_MAX_MEDIA_UPLOAD_BYTES) {
    init {
        ConfigurationValidation.displayName(displayName)
        require(maxMediaUploadBytes in BotDefinition.MIN_MAX_MEDIA_UPLOAD_BYTES..BotDefinition.MAX_MAX_MEDIA_UPLOAD_BYTES) { "maxMediaUploadBytes must be between 1 MiB and 256 MiB" }
    }
}
