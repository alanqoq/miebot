package com.mieai.qqbot.admin.bot

import com.fasterxml.jackson.databind.ObjectMapper
import com.mieai.qqbot.client.MediaAsset
import com.mieai.qqbot.client.MediaAssetStore
import com.mieai.qqbot.client.QqMediaKind
import com.mieai.qqbot.client.QqMessageTargetType
import com.mieai.qqbot.domain.bot.BotDefinition
import com.mieai.qqbot.domain.bot.BotEnvironment
import com.mieai.qqbot.domain.bot.BotId
import com.mieai.qqbot.domain.bot.BotRevision
import com.mieai.qqbot.domain.bot.GatewayIntents
import com.mieai.qqbot.domain.bot.QqAppId
import com.mieai.qqbot.domain.bot.ShardSpec
import com.mieai.qqbot.persistence.bot.BotRepository
import com.mieai.qqbot.persistence.bot.SecretCiphertext
import com.mieai.qqbot.persistence.bot.StoredBot
import com.mieai.qqbot.persistence.outbox.NewOutboxJob
import com.mieai.qqbot.persistence.outbox.OutboxRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mock
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class BotMessageAdministrationServiceTest {
    @Mock lateinit var bots: BotRepository
    @Mock lateinit var outbox: OutboxRepository
    @Mock lateinit var mediaStore: MediaAssetStore
    private lateinit var service: BotMessageAdministrationService

    @BeforeEach
    fun setUp() {
        service = BotMessageAdministrationService(bots, outbox, mediaStore, ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC))
        `when`(bots.findById(BOT_ID)).thenReturn(bot())
    }

    @Test
    fun `rejects a staged asset that exceeds the current bot limit before queueing`() {
        val asset = MediaAsset(ASSET_ID, BOT_ID, QqMediaKind.IMAGE, "photo.png", "image/png", 2L * 1024 * 1024, NOW)
        `when`(mediaStore.find(BOT_ID, ASSET_ID)).thenReturn(asset)
        assertThatThrownBy {
            service.send(BOT_ID.toString(), SendBotMessageRequest(BotMessageKind.MEDIA, QqMessageTargetType.C2C, "user-1", "caption", QqMediaKind.IMAGE, null, ASSET_ID, null, null, null, 1))
        }.isInstanceOf(MediaUploadTooLargeException::class.java)
        val fallbackJob = mock(NewOutboxJob::class.java)
        verify(outbox, never()).create(anyValue(NewOutboxJob::class.java, fallbackJob))
    }

    @Test
    fun `queues rich message as durable outbox payload`() {
        service.send(
            BOT_ID.toString(),
            SendBotMessageRequest(
                BotMessageKind.MARKDOWN,
                QqMessageTargetType.CHANNEL,
                "channel-1",
                null,
                null,
                null,
                null,
                mapOf("content" to "**hello**"),
                null,
                null,
                1,
                SendMessageReferenceRequest("quoted-message-1", true),
            ),
        )
        val captor = ArgumentCaptor.forClass(NewOutboxJob::class.java)
        val fallbackJob = mock(NewOutboxJob::class.java)
        verify(outbox).create(captor.capture() ?: fallbackJob)
        assertThat(captor.value.jobType).isEqualTo("QQ_SEND_RICH")
        assertThat(captor.value.payload)
            .contains("MARKDOWN", "channel-1", "**hello**")
            .contains("\"messageId\":\"quoted-message-1\"")
            .contains("\"ignoreGetMessageError\":true")
    }

    @Test
    fun `rejects unsupported channel media before queueing`() {
        assertThatThrownBy {
            service.send(BOT_ID.toString(), SendBotMessageRequest(BotMessageKind.MEDIA, QqMessageTargetType.CHANNEL, "channel-1", null, QqMediaKind.AUDIO, "https://cdn.example/audio.mp3", null, null, null, null, 1))
        }.isInstanceOf(BotMessageAdministrationException::class.java).hasMessageContaining("only support image")
        val fallbackJob = mock(NewOutboxJob::class.java)
        verify(outbox, never()).create(anyValue(NewOutboxJob::class.java, fallbackJob))
    }

    @Test
    fun `rejects malformed explicit message references before queueing`() {
        assertThatThrownBy {
            service.send(
                BOT_ID.toString(),
                SendBotMessageRequest(
                    BotMessageKind.TEXT,
                    QqMessageTargetType.C2C,
                    "user-1",
                    "hello",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    1,
                    SendMessageReferenceRequest("bad reference", false),
                ),
            )
        }.isInstanceOf(BotMessageAdministrationException::class.java)
            .hasMessageContaining("messageId")
        val fallbackJob = mock(NewOutboxJob::class.java)
        verify(outbox, never()).create(anyValue(NewOutboxJob::class.java, fallbackJob))
    }

    @Test
    fun `rejects malformed rich message targets before queueing`() {
        assertThatThrownBy {
            service.send(BOT_ID.toString(), SendBotMessageRequest(BotMessageKind.MARKDOWN, QqMessageTargetType.C2C, "bad target", null, null, null, null, mapOf("content" to "hello"), null, null, 1))
        }.isInstanceOf(BotMessageAdministrationException::class.java).hasMessageContaining("targetId")
        val fallbackJob = mock(NewOutboxJob::class.java)
        verify(outbox, never()).create(anyValue(NewOutboxJob::class.java, fallbackJob))
    }

    private fun bot() = StoredBot(BotDefinition(BOT_ID, "Test Bot", QqAppId.of("102012345"), BotEnvironment.SANDBOX, GatewayIntents.of(512L), ShardSpec.single(), true, BotRevision.initial(), NOW, NOW, 1024L * 1024), SecretCiphertext.of("ciphertext", "key"))

    private fun <T : Any> anyValue(type: Class<T>, fallback: T): T = any(type) ?: fallback

    private companion object {
        val BOT_ID = BotId.parse("550e8400-e29b-41d4-a716-446655440000")
        val ASSET_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440001")
        val NOW = Instant.parse("2026-07-20T12:00:00Z")
    }
}
