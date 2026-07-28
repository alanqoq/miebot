package com.mieai.qqbot.plugin.api

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/** Controlled media capability kept separate from text message sending. */
interface MediaService {
    fun enqueue(message: MediaMessage): CompletionStage<MessageEnqueueReceipt>

    fun enqueue(
        message: MediaMessage,
        options: MessageSendOptions,
    ): CompletionStage<MessageEnqueueReceipt> = withDefaultOptions(options) { enqueue(message) }

    fun stage(upload: MediaUpload): CompletionStage<StagedMedia>

    fun enqueue(message: StagedMediaMessage): CompletionStage<MessageEnqueueReceipt>

    fun enqueue(
        message: StagedMediaMessage,
        options: MessageSendOptions,
    ): CompletionStage<MessageEnqueueReceipt> = withDefaultOptions(options) { enqueue(message) }

    companion object {
        fun denied(): MediaService = object : MediaService {
            override fun enqueue(message: MediaMessage): CompletionStage<MessageEnqueueReceipt> = deniedResult()

            override fun enqueue(
                message: MediaMessage,
                options: MessageSendOptions,
            ): CompletionStage<MessageEnqueueReceipt> = deniedResult()

            override fun stage(upload: MediaUpload): CompletionStage<StagedMedia> = deniedResult()

            override fun enqueue(message: StagedMediaMessage): CompletionStage<MessageEnqueueReceipt> = deniedResult()

            override fun enqueue(
                message: StagedMediaMessage,
                options: MessageSendOptions,
            ): CompletionStage<MessageEnqueueReceipt> = deniedResult()

            private fun <T> deniedResult(): CompletionStage<T> =
                CompletableFuture.failedFuture(SecurityException("Plugin media capability is not granted"))
        }
    }
}

private fun <T> withDefaultOptions(options: MessageSendOptions, enqueue: () -> CompletionStage<T>): CompletionStage<T> =
    if (options.messageReference == null) {
        enqueue()
    } else {
        CompletableFuture.failedFuture(
            UnsupportedOperationException("This media service does not support explicit message references"),
        )
    }
