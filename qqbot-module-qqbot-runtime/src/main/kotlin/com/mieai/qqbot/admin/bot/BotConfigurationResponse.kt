package com.mieai.qqbot.admin.bot

import com.mieai.qqbot.runtime.configuration.BotConfigurationView
import java.time.Instant
import java.util.UUID

data class BotConfigurationResponse(
    val id: UUID,
    val displayName: String,
    val appId: String,
    val environment: String,
    val intents: Long,
    val shardIndex: Int,
    val shardCount: Int,
    val enabled: Boolean,
    val revision: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
    val secretConfigured: Boolean,
    val maxMediaUploadBytes: Long,
) {
    companion object {
        fun from(view: BotConfigurationView): BotConfigurationResponse = BotConfigurationResponse(
            view.id.value,
            view.displayName,
            view.appId.value,
            view.environment.name,
            view.intents.bits,
            view.shardSpec.index,
            view.shardSpec.count,
            view.enabled,
            view.revision.value,
            view.createdAt,
            view.updatedAt,
            view.secretConfigured,
            view.maxMediaUploadBytes,
        )
    }
}
