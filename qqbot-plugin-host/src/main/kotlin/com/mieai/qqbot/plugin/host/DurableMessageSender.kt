package com.mieai.qqbot.plugin.host

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.mieai.qqbot.client.MediaAssetStore
import com.mieai.qqbot.client.QqClientOptions
import com.mieai.qqbot.client.QqMediaKind
import com.mieai.qqbot.client.QqMessageTargetType
import com.mieai.qqbot.client.QqRichMessageKind
import com.mieai.qqbot.domain.bot.BotEnvironment
import com.mieai.qqbot.domain.bot.BotId
import com.mieai.qqbot.persistence.outbox.NewOutboxJob
import com.mieai.qqbot.persistence.outbox.OutboxJob
import com.mieai.qqbot.persistence.outbox.OutboxRepository
import com.mieai.qqbot.plugin.api.MediaMessage
import com.mieai.qqbot.plugin.api.MediaService
import com.mieai.qqbot.plugin.api.MediaUpload
import com.mieai.qqbot.plugin.api.MessageDeliveryReceipt
import com.mieai.qqbot.plugin.api.MessageDeliveryState
import com.mieai.qqbot.plugin.api.MessageEnqueueReceipt
import com.mieai.qqbot.plugin.api.MessageSendOptions
import com.mieai.qqbot.plugin.api.MessageSender
import com.mieai.qqbot.plugin.api.RichMessage
import com.mieai.qqbot.plugin.api.StagedMedia
import com.mieai.qqbot.plugin.api.StagedMediaMessage
import com.mieai.qqbot.plugin.api.TextMessage
import com.mieai.qqbot.runtime.outbox.OutboundMediaPayload
import com.mieai.qqbot.runtime.outbox.OutboundMessageReference
import com.mieai.qqbot.runtime.outbox.OutboundRichPayload
import com.mieai.qqbot.runtime.outbox.OutboundTextPayload
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

class DurableMessageSender(
    private val bindingId: UUID,
    private val botId: BotId,
    private val environment: BotEnvironment,
    private val repository: OutboxRepository,
    private val mapper: ObjectMapper,
    private val clock: Clock,
    private val capabilityGuard: BindingCapabilityGuard = BindingCapabilityGuard(),
    private val mediaStore: MediaAssetStore? = null,
    private val maxMediaBytes: Long = QqClientOptions.DEFAULT_MAX_MEDIA_BYTES,
) : MessageSender, MediaService {
    init {
        require(maxMediaBytes >= 1L) { "maxMediaBytes must be positive" }
    }

    override fun enqueue(message: TextMessage): CompletionStage<MessageEnqueueReceipt> {
        return enqueue(message, MessageSendOptions())
    }

    override fun enqueue(
        message: TextMessage,
        options: MessageSendOptions,
    ): CompletionStage<MessageEnqueueReceipt> {
        capabilityFailure<MessageEnqueueReceipt>()?.let { return it }
        val now = clock.instant()
        val scopedDedup = message.deduplicationKey?.let { "$bindingId:$it" }
        val jobId = jobId(scopedDedup)
        val payload = try {
            mapper.writeValueAsString(
                OutboundTextPayload(
                    QqMessageTargetType.valueOf(message.target.type.name),
                    message.target.id,
                    message.content,
                    message.replyMessageId,
                    message.replyEventId,
                    message.messageSequence,
                    options.toOutboundReference(),
                ),
            )
        } catch (exception: JsonProcessingException) {
            return CompletableFuture.failedFuture(IllegalArgumentException("Unable to encode text message", exception))
        }
        return createJob(
            NewOutboxJob(
                jobId,
                environment,
                botId,
                message.sourceEventId,
                OutboundTextPayload.JOB_TYPE,
                scopedDedup,
                payload,
                now,
                now,
                bindingId,
            ),
            now,
        )
    }

    override fun enqueue(message: MediaMessage): CompletionStage<MessageEnqueueReceipt> {
        return enqueue(message, MessageSendOptions())
    }

    override fun enqueue(
        message: MediaMessage,
        options: MessageSendOptions,
    ): CompletionStage<MessageEnqueueReceipt> {
        capabilityFailure<MessageEnqueueReceipt>()?.let { return it }
        val now = clock.instant()
        val scopedDedup = message.deduplicationKey?.let { "$bindingId:$it" }
        val jobId = jobId(scopedDedup)
        val payload = try {
            mapper.writeValueAsString(
                OutboundMediaPayload(
                    QqMessageTargetType.valueOf(message.target.type.name),
                    message.target.id,
                    QqMediaKind.valueOf(message.kind.name),
                    message.mediaUrl.toString(),
                    message.content,
                    message.replyMessageId,
                    message.replyEventId,
                    message.messageSequence,
                    messageReference = options.toOutboundReference(),
                ),
            )
        } catch (exception: JsonProcessingException) {
            return CompletableFuture.failedFuture(IllegalArgumentException("Unable to encode media message", exception))
        }
        return createJob(
            NewOutboxJob(
                jobId,
                environment,
                botId,
                message.sourceEventId,
                OutboundMediaPayload.JOB_TYPE,
                scopedDedup,
                payload,
                now,
                now,
                bindingId,
            ),
            now,
        )
    }

    override fun enqueue(message: RichMessage): CompletionStage<MessageEnqueueReceipt> {
        return enqueue(message, MessageSendOptions())
    }

    override fun enqueue(
        message: RichMessage,
        options: MessageSendOptions,
    ): CompletionStage<MessageEnqueueReceipt> {
        capabilityFailure<MessageEnqueueReceipt>()?.let { return it }
        val now = clock.instant()
        val scopedDedup = message.deduplicationKey?.let { "$bindingId:$it" }
        val jobId = jobId(scopedDedup)
        val payload = try {
            mapper.writeValueAsString(
                OutboundRichPayload(
                    QqMessageTargetType.valueOf(message.target.type.name),
                    message.target.id,
                    QqRichMessageKind.valueOf(message.kind.name),
                    message.payload,
                    message.replyMessageId,
                    message.replyEventId,
                    message.messageSequence,
                    options.toOutboundReference(),
                ),
            )
        } catch (exception: JsonProcessingException) {
            return CompletableFuture.failedFuture(IllegalArgumentException("Unable to encode rich message", exception))
        }
        return createJob(
            NewOutboxJob(
                jobId,
                environment,
                botId,
                message.sourceEventId,
                OutboundRichPayload.JOB_TYPE,
                scopedDedup,
                payload,
                now,
                now,
                bindingId,
            ),
            now,
        )
    }

    override fun stage(upload: MediaUpload): CompletionStage<StagedMedia> {
        capabilityFailure<StagedMedia>()?.let { return it }
        val store = mediaStore
        if (store == null) {
            return CompletableFuture.failedFuture(UnsupportedOperationException("media staging is not configured"))
        }
        return CompletableFuture.supplyAsync {
            val asset = store.stage(
                botId,
                QqMediaKind.valueOf(upload.kind.name),
                upload.fileName,
                upload.contentType,
                ByteArrayInputStream(upload.data),
                maxMediaBytes,
            )
            try {
                capabilityGuard.requireActive()
                StagedMedia(asset.id, upload.kind, asset.fileName, asset.sizeBytes)
            } catch (exception: RuntimeException) {
                store.delete(asset)
                throw exception
            }
        }
    }

    override fun enqueue(message: StagedMediaMessage): CompletionStage<MessageEnqueueReceipt> {
        return enqueue(message, MessageSendOptions())
    }

    override fun enqueue(
        message: StagedMediaMessage,
        options: MessageSendOptions,
    ): CompletionStage<MessageEnqueueReceipt> {
        capabilityFailure<MessageEnqueueReceipt>()?.let { return it }
        val store = mediaStore
        if (store == null) {
            return CompletableFuture.failedFuture(IllegalArgumentException("staged media is not available"))
        }
        val asset = store.find(botId, message.media.id)
        if (asset == null || asset.kind != QqMediaKind.valueOf(message.media.kind.name) ||
            asset.sizeBytes != message.media.sizeBytes || asset.sizeBytes > maxMediaBytes
        ) {
            return CompletableFuture.failedFuture(
                IllegalArgumentException("staged media metadata does not match the stored asset"),
            )
        }
        val now = clock.instant()
        val scopedDedup = message.deduplicationKey?.let { "$bindingId:$it" }
        val jobId = jobId(scopedDedup)
        val payload = try {
            mapper.writeValueAsString(
                OutboundMediaPayload(
                    QqMessageTargetType.valueOf(message.target.type.name),
                    message.target.id,
                    QqMediaKind.valueOf(message.media.kind.name),
                    "https://asset.invalid/${message.media.id}",
                    message.content,
                    message.replyMessageId,
                    message.replyEventId,
                    message.messageSequence,
                    message.media.id,
                    options.toOutboundReference(),
                ),
            )
        } catch (exception: JsonProcessingException) {
            return CompletableFuture.failedFuture(IllegalArgumentException("Unable to encode staged media", exception))
        }
        return createJob(
            NewOutboxJob(
                jobId,
                environment,
                botId,
                message.sourceEventId,
                OutboundMediaPayload.JOB_TYPE,
                scopedDedup,
                payload,
                now,
                now,
                bindingId,
            ),
            now,
        )
    }

    override fun findDelivery(jobId: UUID): CompletionStage<MessageDeliveryReceipt?> {
        return try {
            capabilityGuard.requireActive()
            val receipt = repository.findByIdAndProducerBindingId(jobId, bindingId)?.let(::receipt)
            CompletableFuture.completedFuture(receipt)
        } catch (exception: RuntimeException) {
            CompletableFuture.failedFuture(exception)
        }
    }

    private fun createJob(job: NewOutboxJob, queuedAt: java.time.Instant): CompletionStage<MessageEnqueueReceipt> {
        return try {
            capabilityGuard.requireActive()
            repository.create(job)
            CompletableFuture.completedFuture(MessageEnqueueReceipt(job.id, false, queuedAt))
        } catch (exception: RuntimeException) {
            if (repository.findByIdAndProducerBindingId(job.id, bindingId) != null) {
                CompletableFuture.completedFuture(MessageEnqueueReceipt(job.id, true, queuedAt))
            } else {
                CompletableFuture.failedFuture(exception)
            }
        }
    }

    private fun jobId(scopedDedup: String?): UUID = if (scopedDedup == null) {
        UUID.randomUUID()
    } else {
        UUID.nameUUIDFromBytes("$environment:$botId:$scopedDedup".toByteArray(StandardCharsets.UTF_8))
    }

    private fun <T> capabilityFailure(): CompletionStage<T>? = try {
        capabilityGuard.requireActive()
        null
    } catch (exception: RuntimeException) {
        CompletableFuture.failedFuture(exception)
    }

    private fun MessageSendOptions.toOutboundReference(): OutboundMessageReference? =
        messageReference?.let { reference ->
            OutboundMessageReference(reference.messageId, reference.ignoreGetMessageError)
        }

    private fun receipt(job: OutboxJob): MessageDeliveryReceipt = MessageDeliveryReceipt(
        job.id,
        MessageDeliveryState.valueOf(job.status.name),
        job.platformMessageId,
        job.platformMessageSequence,
        job.platformTimestamp,
        job.completedAt,
        job.lastError,
    )
}
