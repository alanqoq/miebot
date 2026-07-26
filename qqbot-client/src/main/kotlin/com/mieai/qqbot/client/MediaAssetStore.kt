package com.mieai.qqbot.client

import com.mieai.qqbot.domain.bot.BotId
import java.io.InputStream
import java.util.UUID

/** Host-owned staging store for bounded web/plugin media uploads. */
interface MediaAssetStore {
    fun stage(
        botId: BotId,
        kind: QqMediaKind,
        fileName: String,
        contentType: String,
        input: InputStream,
        maxBytes: Long,
    ): MediaAsset

    fun find(botId: BotId, id: UUID): MediaAsset?

    fun open(asset: MediaAsset): InputStream

    fun delete(asset: MediaAsset)
}
