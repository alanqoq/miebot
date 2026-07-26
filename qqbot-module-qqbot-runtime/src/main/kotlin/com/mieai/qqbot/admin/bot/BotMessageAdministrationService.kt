package com.mieai.qqbot.admin.bot

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.mieai.qqbot.client.MediaAsset
import com.mieai.qqbot.client.MediaAssetStore
import com.mieai.qqbot.client.QqMediaKind
import com.mieai.qqbot.client.QqMediaMessageRequest
import com.mieai.qqbot.client.QqMessageTargetType
import com.mieai.qqbot.client.QqRichMessageKind
import com.mieai.qqbot.client.QqRichMessageRequest
import com.mieai.qqbot.client.QqTextMessageRequest
import com.mieai.qqbot.domain.bot.BotId
import com.mieai.qqbot.persistence.bot.BotRepository
import com.mieai.qqbot.persistence.outbox.NewOutboxJob
import com.mieai.qqbot.persistence.outbox.OutboxRepository
import com.mieai.qqbot.runtime.outbox.OutboundMediaPayload
import com.mieai.qqbot.runtime.outbox.OutboundRichPayload
import com.mieai.qqbot.runtime.outbox.OutboundTextPayload
import java.net.URI
import java.time.Clock
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service

@Service
class BotMessageAdministrationService(
    private val bots: BotRepository,
    private val outbox: OutboxRepository,
    private val mediaStore: MediaAssetStore,
    private val mapper: ObjectMapper,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun send(botId: String, request: SendBotMessageRequest): BotMessageResponse {
        val id = BotId.parse(botId)
        val bot = bots.findById(id) ?: throw BotMessageAdministrationException(
            HttpStatus.NOT_FOUND,
            "BOT_NOT_FOUND",
            "Bot does not exist",
        )
        if (!bot.definition.enabled) {
            throw BotMessageAdministrationException(HttpStatus.CONFLICT, "BOT_DISABLED", "Bot is disabled")
        }
        val now = clock.instant()
        val jobId = UUID.randomUUID()
        var stagedAsset: MediaAsset? = null
        val encoded = try {
            val targetType = requireNotNull(request.targetType) { "targetType must not be null" }
            val targetId = requireNotNull(request.targetId) { "targetId must not be null" }
            val sequence = request.messageSequence ?: 1
            val messageKind = request.kind
            when (messageKind) {
                BotMessageKind.TEXT -> {
                    val content = request.content
                    requireText(content)
                    QqTextMessageRequest(
                        targetType,
                        targetId,
                        content!!,
                        request.replyMessageId,
                        request.replyEventId,
                        sequence,
                    )
                    EncodedMessage(
                        OutboundTextPayload.JOB_TYPE,
                        mapper.writeValueAsString(
                            OutboundTextPayload(
                                targetType,
                                targetId,
                                content,
                                request.replyMessageId,
                                request.replyEventId,
                                sequence,
                            ),
                        ),
                    )
                }
                BotMessageKind.MEDIA -> {
                    val mediaKind = request.mediaKind ?: throw invalid("mediaKind is required")
                    if ((targetType == QqMessageTargetType.CHANNEL || targetType == QqMessageTargetType.DIRECT) &&
                        mediaKind != QqMediaKind.IMAGE
                    ) {
                        throw invalid("Channel and direct messages only support image media")
                    }
                    val assetId = request.mediaAssetId
                    var url = request.mediaUrl
                    if (assetId == null && url.isNullOrBlank()) {
                        throw invalid("mediaUrl or mediaAssetId is required")
                    }
                    if (assetId != null) {
                        val asset = mediaStore.find(id, assetId)
                            ?: throw invalid("mediaAssetId is not available")
                        stagedAsset = asset
                        if (asset.sizeBytes > bot.definition.maxMediaUploadBytes) {
                            throw MediaUploadTooLargeException(
                                asset.sizeBytes,
                                bot.definition.maxMediaUploadBytes,
                            )
                        }
                        if (asset.kind != mediaKind) throw invalid("mediaAssetId does not match mediaKind")
                        url = "https://asset.invalid/$assetId"
                    }
                    val resolvedUrl = requireNotNull(url)
                    QqMediaMessageRequest(
                        targetType,
                        targetId,
                        mediaKind,
                        URI.create(resolvedUrl),
                        request.content,
                        request.replyMessageId,
                        request.replyEventId,
                        sequence,
                    )
                    EncodedMessage(
                        OutboundMediaPayload.JOB_TYPE,
                        mapper.writeValueAsString(
                            OutboundMediaPayload(
                                targetType,
                                targetId,
                                mediaKind,
                                resolvedUrl,
                                request.content,
                                request.replyMessageId,
                                request.replyEventId,
                                sequence,
                                assetId,
                            ),
                        ),
                    )
                }
                BotMessageKind.MARKDOWN,
                BotMessageKind.KEYBOARD,
                BotMessageKind.ARK,
                BotMessageKind.EMBED,
                -> {
                    val body = request.payload ?: emptyMap()
                    if (body.isEmpty()) throw invalid("payload is required for rich messages")
                    val kind = QqRichMessageKind.valueOf(messageKind.name)
                    QqRichMessageRequest(
                        targetType,
                        targetId,
                        kind,
                        body,
                        request.replyMessageId,
                        request.replyEventId,
                        sequence,
                    )
                    EncodedMessage(
                        OutboundRichPayload.JOB_TYPE,
                        mapper.writeValueAsString(
                            OutboundRichPayload(
                                targetType,
                                targetId,
                                kind,
                                body,
                                request.replyMessageId,
                                request.replyEventId,
                                sequence,
                            ),
                        ),
                    )
                }
                null -> throw NullPointerException("kind must not be null")
            }
        } catch (_: JsonProcessingException) {
            deleteQuietly(stagedAsset)
            throw BotMessageAdministrationException(
                HttpStatus.BAD_REQUEST,
                "INVALID_MESSAGE",
                "Message payload is invalid",
            )
        } catch (exception: IllegalArgumentException) {
            deleteQuietly(stagedAsset)
            throw invalid(exception.message ?: "Message payload is invalid")
        } catch (exception: NullPointerException) {
            deleteQuietly(stagedAsset)
            throw invalid(exception.message ?: "Message payload is invalid")
        } catch (exception: RuntimeException) {
            deleteQuietly(stagedAsset)
            throw exception
        }

        try {
            outbox.create(
                NewOutboxJob(
                    jobId,
                    bot.definition.environment,
                    id,
                    null,
                    encoded.type,
                    null,
                    encoded.payload,
                    now,
                    now,
                    null,
                ),
            )
        } catch (exception: RuntimeException) {
            deleteQuietly(stagedAsset)
            throw exception
        }
        return BotMessageResponse(jobId, false, now)
    }

    private fun deleteQuietly(asset: MediaAsset?) {
        if (asset == null) return
        try {
            mediaStore.delete(asset)
        } catch (_: RuntimeException) {
            // Preserve the request failure; the staged directory remains operator-recoverable.
        }
    }

    private data class EncodedMessage(val type: String, val payload: String)

    private companion object {
        fun requireText(value: String?) {
            if (value.isNullOrBlank()) throw invalid("content is required")
        }

        fun invalid(message: String) =
            BotMessageAdministrationException(HttpStatus.BAD_REQUEST, "INVALID_MESSAGE", message)
    }
}
