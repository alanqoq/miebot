package com.mieai.qqbot.client

import com.mieai.qqbot.domain.bot.BotId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class FileMediaAssetStoreTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun persistsMetadataAndCanReopenTheAssetAfterStoreRestart() {
        val store = FileMediaAssetStore(directory, Clock.fixed(NOW, ZoneOffset.UTC))
        val bytes = byteArrayOf(1, 2, 3, 4)
        val staged = store.stage(
            BOT_ID,
            QqMediaKind.IMAGE,
            "photo.png",
            "image/png",
            ByteArrayInputStream(bytes),
            16L,
        )

        val reopened = FileMediaAssetStore(directory, Clock.systemUTC())
        val found = requireNotNull(reopened.find(BOT_ID, staged.id))
        assertThat(found).isEqualTo(staged)
        reopened.open(found).use { input -> assertThat(input.readAllBytes()).containsExactly(*bytes) }

        reopened.delete(found)
        assertThat(reopened.find(BOT_ID, staged.id)).isNull()
        Files.list(directory.resolve(BOT_ID.toString())).use { files -> assertThat(files).isEmpty() }
    }

    @Test
    fun rejectsOversizedContentAndRemovesPartialFiles() {
        val store = FileMediaAssetStore(directory, Clock.fixed(NOW, ZoneOffset.UTC))

        assertThatThrownBy {
            store.stage(
                BOT_ID,
                QqMediaKind.FILE,
                "data.bin",
                "application/octet-stream",
                ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)),
                3L,
            )
        }.isInstanceOf(FileMediaAssetStore.MediaSizeExceededException::class.java)

        Files.list(directory.resolve(BOT_ID.toString())).use { files -> assertThat(files).isEmpty() }
    }

    companion object {
        private val BOT_ID = BotId.parse("550e8400-e29b-41d4-a716-446655440000")
        private val NOW = Instant.parse("2026-07-20T12:00:00Z")
    }
}
