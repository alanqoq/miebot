package com.mieai.qqbot.plugin.api

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/** Controlled media capability kept separate from text message sending. */
interface MediaService {
    fun enqueue(message: MediaMessage): CompletionStage<MessageEnqueueReceipt>

    fun stage(upload: MediaUpload): CompletionStage<StagedMedia>

    fun enqueue(message: StagedMediaMessage): CompletionStage<MessageEnqueueReceipt>

    companion object {
        fun denied(): MediaService = object : MediaService {
            override fun enqueue(message: MediaMessage): CompletionStage<MessageEnqueueReceipt> = deniedResult()

            override fun stage(upload: MediaUpload): CompletionStage<StagedMedia> = deniedResult()

            override fun enqueue(message: StagedMediaMessage): CompletionStage<MessageEnqueueReceipt> = deniedResult()

            private fun <T> deniedResult(): CompletionStage<T> =
                CompletableFuture.failedFuture(SecurityException("Plugin media capability is not granted"))
        }
    }
}
