package com.mieai.qqbot.client

import com.mieai.qqbot.domain.bot.BotId
import java.time.Instant
import java.util.UUID

/** Metadata for a bounded local media asset staged for a bot-scoped send. */
data class MediaAsset(
    val id: UUID,
    val botId: BotId,
    val kind: QqMediaKind,
    val fileName: String,
    val contentType: String,
    val sizeBytes: Long,
    val createdAt: Instant,
) {
    init {
        require(!fileName.isBlank() && fileName.length <= 255) { "fileName is invalid" }
        require(!contentType.isBlank() && contentType.length <= 128) { "contentType is invalid" }
        require(sizeBytes >= 1L) { "sizeBytes must be positive" }
    }
}
