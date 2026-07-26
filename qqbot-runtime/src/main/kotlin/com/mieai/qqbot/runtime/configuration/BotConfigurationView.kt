package com.mieai.qqbot.runtime.configuration

import com.mieai.qqbot.domain.bot.*
import java.time.Instant

data class BotConfigurationView(val id: BotId, val displayName: String, val appId: QqAppId, val environment: BotEnvironment, val intents: GatewayIntents, val shardSpec: ShardSpec, val enabled: Boolean, val revision: BotRevision, val createdAt: Instant, val updatedAt: Instant, val secretConfigured: Boolean, val maxMediaUploadBytes: Long = BotDefinition.DEFAULT_MAX_MEDIA_UPLOAD_BYTES) {
    init {
        ConfigurationValidation.displayName(displayName)
        require(maxMediaUploadBytes in BotDefinition.MIN_MAX_MEDIA_UPLOAD_BYTES..BotDefinition.MAX_MAX_MEDIA_UPLOAD_BYTES) { "maxMediaUploadBytes must be between 1 MiB and 256 MiB" }
    }
}
