package com.mieai.qqbot.plugin.testkit;

import com.mieai.qqbot.plugin.api.MediaMessage;
import com.mieai.qqbot.plugin.api.MediaService;
import com.mieai.qqbot.plugin.api.MessageEnqueueReceipt;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Recording media capability for plugin unit tests. */
public final class FakeMediaService implements MediaService {
    private final Clock clock;
    private final List<MediaMessage> messages = new ArrayList<>();
    private RuntimeException failure;

    public FakeMediaService() {
        this(Clock.systemUTC());
    }

    public FakeMediaService(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public synchronized CompletionStage<MessageEnqueueReceipt> enqueue(MediaMessage message) {
        if (failure != null) return CompletableFuture.failedFuture(failure);
        messages.add(Objects.requireNonNull(message, "message must not be null"));
        return CompletableFuture.completedFuture(
                new MessageEnqueueReceipt(UUID.randomUUID(), false, clock.instant()));
    }

    public synchronized List<MediaMessage> messages() {
        return List.copyOf(messages);
    }

    public synchronized void failWith(RuntimeException value) {
        failure = Objects.requireNonNull(value, "value must not be null");
    }

    public synchronized void clearFailure() {
        failure = null;
    }
}
