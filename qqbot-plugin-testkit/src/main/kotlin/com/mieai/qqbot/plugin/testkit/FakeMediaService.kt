package com.mieai.qqbot.plugin.testkit

import com.mieai.qqbot.plugin.api.MediaMessage
import com.mieai.qqbot.plugin.api.MediaService
import com.mieai.qqbot.plugin.api.MediaUpload
import com.mieai.qqbot.plugin.api.MessageEnqueueReceipt
import com.mieai.qqbot.plugin.api.MessageSendOptions
import com.mieai.qqbot.plugin.api.StagedMedia
import com.mieai.qqbot.plugin.api.StagedMediaMessage
import java.time.Clock
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/** Recording media capability for plugin unit tests. */
class FakeMediaService(
    private val clock: Clock = Clock.systemUTC(),
) : MediaService {
    private val messages = mutableListOf<MediaMessage>()
    private val messageOptions = mutableListOf<MessageSendOptions>()
    private val uploads = mutableListOf<MediaUpload>()
    private val stagedMessages = mutableListOf<StagedMediaMessage>()
    private val stagedMessageOptions = mutableListOf<MessageSendOptions>()
    private var failure: RuntimeException? = null

    @Synchronized
    override fun enqueue(message: MediaMessage): CompletionStage<MessageEnqueueReceipt> =
        enqueue(message, MessageSendOptions())

    @Synchronized
    override fun enqueue(
        message: MediaMessage,
        options: MessageSendOptions,
    ): CompletionStage<MessageEnqueueReceipt> {
        failure?.let { return CompletableFuture.failedFuture<MessageEnqueueReceipt>(it) }
        messages.add(message)
        messageOptions.add(options)
        return CompletableFuture.completedFuture(MessageEnqueueReceipt(UUID.randomUUID(), false, clock.instant()))
    }

    @Synchronized
    override fun stage(upload: MediaUpload): CompletionStage<StagedMedia> {
        failure?.let { return CompletableFuture.failedFuture(it) }
        uploads.add(upload)
        return CompletableFuture.completedFuture(
            StagedMedia(UUID.randomUUID(), upload.kind, upload.fileName, upload.data.size.toLong()),
        )
    }

    @Synchronized
    override fun enqueue(message: StagedMediaMessage): CompletionStage<MessageEnqueueReceipt> =
        enqueue(message, MessageSendOptions())

    @Synchronized
    override fun enqueue(
        message: StagedMediaMessage,
        options: MessageSendOptions,
    ): CompletionStage<MessageEnqueueReceipt> {
        failure?.let { return CompletableFuture.failedFuture(it) }
        stagedMessages.add(message)
        stagedMessageOptions.add(options)
        return CompletableFuture.completedFuture(MessageEnqueueReceipt(UUID.randomUUID(), false, clock.instant()))
    }

    @Synchronized
    fun messages(): List<MediaMessage> = messages.toList()

    @Synchronized
    fun messageSendOptions(): List<MessageSendOptions> = messageOptions.toList()

    @Synchronized
    fun uploads(): List<MediaUpload> = uploads.toList()

    @Synchronized
    fun stagedMessages(): List<StagedMediaMessage> = stagedMessages.toList()

    @Synchronized
    fun stagedMessageSendOptions(): List<MessageSendOptions> = stagedMessageOptions.toList()

    @Synchronized
    fun failWith(value: RuntimeException) {
        failure = value
    }

    @Synchronized
    fun clearFailure() {
        failure = null
    }
}
