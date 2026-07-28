package com.mieai.qqbot.plugin.host

import com.fasterxml.jackson.databind.ObjectMapper
import com.mieai.qqbot.client.MediaAsset
import com.mieai.qqbot.client.MediaAssetStore
import com.mieai.qqbot.client.QqClientOptions
import com.mieai.qqbot.client.QqMediaKind
import com.mieai.qqbot.domain.bot.BotEnvironment
import com.mieai.qqbot.domain.bot.BotId
import com.mieai.qqbot.persistence.outbox.OutboxJob
import com.mieai.qqbot.persistence.outbox.NewOutboxJob
import com.mieai.qqbot.persistence.outbox.OutboxRepository
import com.mieai.qqbot.persistence.outbox.OutboxStatus
import com.mieai.qqbot.plugin.api.MediaKind
import com.mieai.qqbot.plugin.api.MessageDeliveryState
import com.mieai.qqbot.plugin.api.MessageReference
import com.mieai.qqbot.plugin.api.MessageSendOptions
import com.mieai.qqbot.plugin.api.MessageTarget
import com.mieai.qqbot.plugin.api.MessageTargetType
import com.mieai.qqbot.plugin.api.StagedMedia
import com.mieai.qqbot.plugin.api.StagedMediaMessage
import com.mieai.qqbot.plugin.api.TextMessage
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CompletionException

@ExtendWith(MockitoExtension::class)
class DurableMessageSenderTest {
    @Mock
    lateinit var outbox: OutboxRepository

    @Mock
    lateinit var mediaStore: MediaAssetStore

    @Test
    fun rejectsForgedStagedMediaMetadataBeforeCreatingAnOutboxJob() {
        val stored = MediaAsset(ASSET_ID, BOT_ID, QqMediaKind.IMAGE, "photo.png", "image/png", 4L, NOW)
        `when`(mediaStore.find(BOT_ID, ASSET_ID)).thenReturn(stored)
        val sender = DurableMessageSender(
            UUID.randomUUID(),
            BOT_ID,
            BotEnvironment.SANDBOX,
            outbox,
            ObjectMapper(),
            Clock.fixed(NOW, ZoneOffset.UTC),
            BindingCapabilityGuard(),
            mediaStore,
            16L * 1024L * 1024L,
        )
        val forged = StagedMedia(ASSET_ID, MediaKind.AUDIO, "photo.png", 4L)
        val message = StagedMediaMessage(
            MessageTarget(MessageTargetType.C2C, "user-1"),
            forged,
        )

        assertThatThrownBy { sender.enqueue(message).toCompletableFuture().join() }
            .isInstanceOf(CompletionException::class.java)
            .hasCauseInstanceOf(IllegalArgumentException::class.java)
        verifyNoInteractions(outbox)
    }

    @Test
    fun persistsExplicitMessageReferenceInTheOutboxPayload() {
        val sender = DurableMessageSender(
            UUID.randomUUID(),
            BOT_ID,
            BotEnvironment.SANDBOX,
            outbox,
            ObjectMapper(),
            Clock.fixed(NOW, ZoneOffset.UTC),
        )

        sender.enqueue(
            TextMessage(MessageTarget(MessageTargetType.GROUP, "group-1"), "hello"),
            MessageSendOptions(MessageReference("bot-message-900", true)),
        ).toCompletableFuture().join()

        val captor = ArgumentCaptor.forClass(NewOutboxJob::class.java)
        val fallback = mock(NewOutboxJob::class.java)
        verify(outbox).create(captor.capture() ?: fallback)
        assertThat(captor.value.payload)
            .contains("\"messageReference\"")
            .contains("\"messageId\":\"bot-message-900\"")
            .contains("\"ignoreGetMessageError\":true")
    }

    @Test
    fun exposesOnlyDeliveryReceiptsOwnedByTheCurrentBinding() {
        val bindingId = UUID.fromString("650e8400-e29b-41d4-a716-446655440001")
        val jobId = UUID.fromString("750e8400-e29b-41d4-a716-446655440001")
        val sender = DurableMessageSender(
            bindingId,
            BOT_ID,
            BotEnvironment.SANDBOX,
            outbox,
            ObjectMapper(),
            Clock.fixed(NOW, ZoneOffset.UTC),
            BindingCapabilityGuard(),
            null,
            QqClientOptions.DEFAULT_MAX_MEDIA_BYTES,
        )
        val job = OutboxJob(
            jobId,
            BotEnvironment.SANDBOX,
            BOT_ID,
            null,
            "SEND_TEXT",
            null,
            "{}",
            OutboxStatus.SUCCEEDED,
            1L,
            NOW,
            null,
            null,
            1L,
            null,
            NOW,
            NOW,
            NOW,
            bindingId,
            "bot-message-900",
            3,
            "2026-07-24T12:00:00Z",
        )
        `when`(outbox.findByIdAndProducerBindingId(jobId, bindingId)).thenReturn(job)

        val receipt = requireNotNull(sender.findDelivery(jobId).toCompletableFuture().join())

        assertThat(receipt.state).isEqualTo(MessageDeliveryState.SUCCEEDED)
        assertThat(receipt.platformMessageId).isEqualTo("bot-message-900")
        assertThat(receipt.platformMessageSequence).isEqualTo(3)
    }

    companion object {
        private val BOT_ID = BotId.parse("550e8400-e29b-41d4-a716-446655440000")
        private val ASSET_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440001")
        private val NOW = Instant.parse("2026-07-20T12:00:00Z")
    }
}
