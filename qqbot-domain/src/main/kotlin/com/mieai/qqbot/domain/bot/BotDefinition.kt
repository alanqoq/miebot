package com.mieai.qqbot.domain.bot

import java.time.Instant

/** Persisted bot configuration without credentials or runtime connection state. */
data class BotDefinition(
    val id: BotId,
    val displayName: String,
    val appId: QqAppId,
    val environment: BotEnvironment,
    val intents: GatewayIntents,
    val shardSpec: ShardSpec,
    val enabled: Boolean,
    val revision: BotRevision,
    val createdAt: Instant,
    val updatedAt: Instant,
    val maxMediaUploadBytes: Long = DEFAULT_MAX_MEDIA_UPLOAD_BYTES,
) {
    init {
        require(displayName.isNotBlank()) { "displayName must not be blank" }
        require(displayName == displayName.trim()) { "displayName must not have surrounding whitespace" }
        require(displayName.codePoints().noneMatch { Character.isISOControl(it) }) {
            "displayName must not contain control characters"
        }
        require(!updatedAt.isBefore(createdAt)) { "updatedAt must not be before createdAt" }
        require(maxMediaUploadBytes in MIN_MAX_MEDIA_UPLOAD_BYTES..MAX_MAX_MEDIA_UPLOAD_BYTES) {
            "maxMediaUploadBytes must be between 1 MiB and 256 MiB"
        }
    }

    companion object {
        val DEFAULT_MAX_MEDIA_UPLOAD_BYTES = 16L * 1024L * 1024L

        val MIN_MAX_MEDIA_UPLOAD_BYTES = 1024L * 1024L

        val MAX_MAX_MEDIA_UPLOAD_BYTES = 256L * 1024L * 1024L
    }
}
