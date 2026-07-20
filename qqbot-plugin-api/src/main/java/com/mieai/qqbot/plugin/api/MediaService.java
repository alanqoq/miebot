package com.mieai.qqbot.plugin.api;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Controlled media capability kept separate from text message sending. */
@FunctionalInterface
public interface MediaService {
    CompletionStage<MessageEnqueueReceipt> enqueue(MediaMessage message);

    default CompletionStage<StagedMedia> stage(MediaUpload upload) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("media staging is not supported"));
    }

    default CompletionStage<MessageEnqueueReceipt> enqueue(StagedMediaMessage message) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("staged media is not supported"));
    }

    static MediaService denied() {
        return message -> CompletableFuture.failedFuture(
                new SecurityException("Plugin media capability is not granted"));
    }
}
