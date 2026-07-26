package com.mieai.qqbot.plugin.testkit

import com.mieai.qqbot.plugin.api.MediaMessage
import com.mieai.qqbot.plugin.api.MediaService
import com.mieai.qqbot.plugin.api.MediaUpload
import com.mieai.qqbot.plugin.api.MessageEnqueueReceipt
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
    private val uploads = mutableListOf<MediaUpload>()
    private val stagedMessages = mutableListOf<StagedMediaMessage>()
    private var failure: RuntimeException? = null

    @Synchronized
    override fun enqueue(message: MediaMessage): CompletionStage<MessageEnqueueReceipt> {
        failure?.let { return CompletableFuture.failedFuture<MessageEnqueueReceipt>(it) }
        messages.add(message)
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
    override fun enqueue(message: StagedMediaMessage): CompletionStage<MessageEnqueueReceipt> {
        failure?.let { return CompletableFuture.failedFuture(it) }
        stagedMessages.add(message)
        return CompletableFuture.completedFuture(MessageEnqueueReceipt(UUID.randomUUID(), false, clock.instant()))
    }

    @Synchronized
    fun messages(): List<MediaMessage> = messages.toList()

    @Synchronized
    fun uploads(): List<MediaUpload> = uploads.toList()

    @Synchronized
    fun stagedMessages(): List<StagedMediaMessage> = stagedMessages.toList()

    @Synchronized
    fun failWith(value: RuntimeException) {
        failure = value
    }

    @Synchronized
    fun clearFailure() {
        failure = null
    }
}
