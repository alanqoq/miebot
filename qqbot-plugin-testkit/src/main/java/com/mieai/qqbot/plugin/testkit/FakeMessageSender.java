package com.mieai.qqbot.plugin.testkit;

import com.mieai.qqbot.plugin.api.MediaMessage;
import com.mieai.qqbot.plugin.api.MessageEnqueueReceipt;
import com.mieai.qqbot.plugin.api.MessageSender;
import com.mieai.qqbot.plugin.api.TextMessage;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Recording sender for plugin unit tests. */
public final class FakeMessageSender implements MessageSender {
    private final Clock clock;
    private final List<TextMessage> textMessages = new ArrayList<>();
    private final List<MediaMessage> mediaMessages = new ArrayList<>();
    private RuntimeException failure;

    public FakeMessageSender() {
        this(Clock.systemUTC());
    }

    public FakeMessageSender(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public synchronized CompletionStage<MessageEnqueueReceipt> enqueue(TextMessage message) {
        if (failure != null) return CompletableFuture.failedFuture(failure);
        textMessages.add(Objects.requireNonNull(message, "message must not be null"));
        return receipt();
    }

    @Override
    public synchronized CompletionStage<MessageEnqueueReceipt> enqueue(MediaMessage message) {
        if (failure != null) return CompletableFuture.failedFuture(failure);
        mediaMessages.add(Objects.requireNonNull(message, "message must not be null"));
        return receipt();
    }

    public synchronized List<TextMessage> textMessages() {
        return List.copyOf(textMessages);
    }

    public synchronized List<MediaMessage> mediaMessages() {
        return List.copyOf(mediaMessages);
    }

    public synchronized void failWith(RuntimeException value) {
        failure = Objects.requireNonNull(value, "value must not be null");
    }

    public synchronized void clearFailure() {
        failure = null;
    }

    private CompletionStage<MessageEnqueueReceipt> receipt() {
        return CompletableFuture.completedFuture(
                new MessageEnqueueReceipt(UUID.randomUUID(), false, clock.instant()));
    }
}
