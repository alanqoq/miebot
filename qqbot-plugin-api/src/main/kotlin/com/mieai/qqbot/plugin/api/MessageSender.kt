package com.mieai.qqbot.plugin.api

import java.util.UUID
import java.util.concurrent.CompletionStage

/** Controlled capability; implementations enqueue durable Outbox work, never expose tokens. */
interface MessageSender {
    fun enqueue(message: TextMessage): CompletionStage<MessageEnqueueReceipt>

    fun enqueue(message: MediaMessage): CompletionStage<MessageEnqueueReceipt>

    fun enqueue(message: RichMessage): CompletionStage<MessageEnqueueReceipt>

    /**
     * Reads the durable lifecycle and QQ delivery result for a previously enqueued message.
     * Results are scoped to the current plugin binding and remain queryable after plugin restart.
     */
    fun findDelivery(jobId: UUID): CompletionStage<MessageDeliveryReceipt?>
}
