package com.mieai.qqbot.admin.bot

import com.mieai.qqbot.client.MediaAsset
import java.time.Instant
import java.util.UUID

data class MediaUploadResponse(
    val id: UUID,
    val botId: UUID,
    val kind: String,
    val fileName: String,
    val contentType: String,
    val sizeBytes: Long,
    val createdAt: Instant,
) {
    companion object {
        fun from(asset: MediaAsset) = MediaUploadResponse(
            asset.id,
            asset.botId.value,
            asset.kind.name,
            asset.fileName,
            asset.contentType,
            asset.sizeBytes,
            asset.createdAt,
        )
    }
}
