package com.mieai.qqbot.plugin.api;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Controlled media capability kept separate from text message sending. */
@FunctionalInterface
public interface MediaService {
    CompletionStage<MessageEnqueueReceipt> enqueue(MediaMessage message);

    static MediaService denied() {
        return message -> CompletableFuture.failedFuture(
                new SecurityException("Plugin media capability is not granted"));
    }
}
