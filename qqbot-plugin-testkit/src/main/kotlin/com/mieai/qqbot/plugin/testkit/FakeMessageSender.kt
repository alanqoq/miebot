package com.mieai.qqbot.plugin.testkit

import com.mieai.qqbot.plugin.api.MediaMessage
import com.mieai.qqbot.plugin.api.MessageDeliveryReceipt
import com.mieai.qqbot.plugin.api.MessageDeliveryState
import com.mieai.qqbot.plugin.api.MessageEnqueueReceipt
import com.mieai.qqbot.plugin.api.MessageSender
import com.mieai.qqbot.plugin.api.RichMessage
import com.mieai.qqbot.plugin.api.TextMessage
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/** Recording sender for plugin unit tests. */
class FakeMessageSender(
    private val clock: Clock = Clock.systemUTC(),
) : MessageSender {
    private val textMessages = mutableListOf<TextMessage>()
    private val mediaMessages = mutableListOf<MediaMessage>()
    private val richMessages = mutableListOf<RichMessage>()
    private val deliveries = mutableMapOf<UUID, MessageDeliveryReceipt>()
    private var failure: RuntimeException? = null

    @Synchronized
    override fun enqueue(message: TextMessage): CompletionStage<MessageEnqueueReceipt> {
        failure?.let { return CompletableFuture.failedFuture<MessageEnqueueReceipt>(it) }
        textMessages.add(message)
        return receipt()
    }

    @Synchronized
    override fun enqueue(message: MediaMessage): CompletionStage<MessageEnqueueReceipt> {
        failure?.let { return CompletableFuture.failedFuture<MessageEnqueueReceipt>(it) }
        mediaMessages.add(message)
        return receipt()
    }

    @Synchronized
    override fun enqueue(message: RichMessage): CompletionStage<MessageEnqueueReceipt> {
        failure?.let { return CompletableFuture.failedFuture<MessageEnqueueReceipt>(it) }
        richMessages.add(message)
        return receipt()
    }

    @Synchronized
    fun textMessages(): List<TextMessage> = textMessages.toList()

    @Synchronized
    fun mediaMessages(): List<MediaMessage> = mediaMessages.toList()

    @Synchronized
    fun richMessages(): List<RichMessage> = richMessages.toList()

    @Synchronized
    fun failWith(value: RuntimeException) {
        failure = value
    }

    @Synchronized
    fun clearFailure() {
        failure = null
    }

    @Synchronized
    override fun findDelivery(jobId: UUID): CompletionStage<MessageDeliveryReceipt?> {
        failure?.let { return CompletableFuture.failedFuture(it) }
        return CompletableFuture.completedFuture(deliveries[jobId])
    }

    @Synchronized
    fun setDelivery(receipt: MessageDeliveryReceipt) {
        deliveries[receipt.jobId] = receipt
    }

    @Synchronized
    fun succeed(jobId: UUID, platformMessageId: String) {
        val completedAt: Instant = clock.instant()
        setDelivery(
            MessageDeliveryReceipt(
                jobId,
                MessageDeliveryState.SUCCEEDED,
                platformMessageId,
                null,
                null,
                completedAt,
                null,
            ),
        )
    }

    private fun receipt(): CompletionStage<MessageEnqueueReceipt> {
        val jobId = UUID.randomUUID()
        val queuedAt = clock.instant()
        deliveries[jobId] = MessageDeliveryReceipt(
            jobId,
            MessageDeliveryState.PENDING,
            null,
            null,
            null,
            null,
            null,
        )
        return CompletableFuture.completedFuture(MessageEnqueueReceipt(jobId, false, queuedAt))
    }
}
