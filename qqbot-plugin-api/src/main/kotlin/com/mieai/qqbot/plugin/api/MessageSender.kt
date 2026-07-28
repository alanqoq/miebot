package com.mieai.qqbot.plugin.api

import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/** Controlled capability; implementations enqueue durable Outbox work, never expose tokens. */
interface MessageSender {
    fun enqueue(message: TextMessage): CompletionStage<MessageEnqueueReceipt>

    fun enqueue(
        message: TextMessage,
        options: MessageSendOptions,
    ): CompletionStage<MessageEnqueueReceipt> = withDefaultOptions(options) { enqueue(message) }

    fun enqueue(message: MediaMessage): CompletionStage<MessageEnqueueReceipt>

    fun enqueue(
        message: MediaMessage,
        options: MessageSendOptions,
    ): CompletionStage<MessageEnqueueReceipt> = withDefaultOptions(options) { enqueue(message) }

    fun enqueue(message: RichMessage): CompletionStage<MessageEnqueueReceipt>

    fun enqueue(
        message: RichMessage,
        options: MessageSendOptions,
    ): CompletionStage<MessageEnqueueReceipt> = withDefaultOptions(options) { enqueue(message) }

    /**
     * Reads the durable lifecycle and QQ delivery result for a previously enqueued message.
     * Results are scoped to the current plugin binding and remain queryable after plugin restart.
     */
    fun findDelivery(jobId: UUID): CompletionStage<MessageDeliveryReceipt?>
}

private fun <T> withDefaultOptions(options: MessageSendOptions, enqueue: () -> CompletionStage<T>): CompletionStage<T> =
    if (options.messageReference == null) {
        enqueue()
    } else {
        CompletableFuture.failedFuture(
            UnsupportedOperationException("This message sender does not support explicit message references"),
        )
    }
