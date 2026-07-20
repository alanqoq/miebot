package com.mieai.qqbot.plugin.api;

import java.util.concurrent.CompletionStage;

/** Controlled capability; implementations enqueue durable Outbox work, never expose tokens. */
@FunctionalInterface
public interface MessageSender {
    CompletionStage<MessageEnqueueReceipt> enqueue(TextMessage message);

    default CompletionStage<MessageEnqueueReceipt> enqueue(MediaMessage message) {
        return java.util.concurrent.CompletableFuture.failedFuture(
                new UnsupportedOperationException("media messages are not supported by this sender"));
    }

    default CompletionStage<MessageEnqueueReceipt> enqueue(RichMessage message) {
        return java.util.concurrent.CompletableFuture.failedFuture(
                new UnsupportedOperationException("rich messages are not supported by this sender"));
    }
}
