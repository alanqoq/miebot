package com.mieai.qqbot.plugin.host;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mieai.qqbot.domain.bot.BotEnvironment;
import com.mieai.qqbot.domain.bot.BotId;
import com.mieai.qqbot.persistence.outbox.NewOutboxJob;
import com.mieai.qqbot.persistence.outbox.OutboxRepository;
import com.mieai.qqbot.plugin.api.MessageEnqueueReceipt;
import com.mieai.qqbot.plugin.api.MessageSender;
import com.mieai.qqbot.plugin.api.MediaMessage;
import com.mieai.qqbot.plugin.api.TextMessage;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

final class DurableMessageSender implements MessageSender {
    private final UUID bindingId;
    private final BotId botId;
    private final BotEnvironment environment;
    private final OutboxRepository repository;
    private final ObjectMapper mapper;
    private final Clock clock;

    DurableMessageSender(UUID bindingId, BotId botId, BotEnvironment environment,
            OutboxRepository repository, ObjectMapper mapper, Clock clock) {
        this.bindingId = Objects.requireNonNull(bindingId, "bindingId must not be null");
        this.botId = Objects.requireNonNull(botId, "botId must not be null");
        this.environment = Objects.requireNonNull(environment, "environment must not be null");
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public CompletionStage<MessageEnqueueReceipt> enqueue(TextMessage message) {
        Objects.requireNonNull(message, "message must not be null");
        Instant now = clock.instant();
        String scopedDedup = message.deduplicationKey().map(value -> bindingId + ":" + value).orElse(null);
        UUID jobId = scopedDedup == null ? UUID.randomUUID() : UUID.nameUUIDFromBytes(
                (environment + ":" + botId + ":" + scopedDedup).getBytes(StandardCharsets.UTF_8));
        String payload;
        try {
            payload = mapper.writeValueAsString(new OutboundTextPayload(
                    message.target().type(), message.target().id(), message.content(),
                    message.replyMessageId().orElse(null), message.replyEventId().orElse(null),
                    message.messageSequence()));
        } catch (JsonProcessingException exception) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Unable to encode text message", exception));
        }
        try {
            repository.create(new NewOutboxJob(jobId, environment, botId, message.sourceEventId(),
                    OutboundTextPayload.JOB_TYPE, Optional.ofNullable(scopedDedup), payload, now, now));
            return CompletableFuture.completedFuture(new MessageEnqueueReceipt(jobId, false, now));
        } catch (RuntimeException exception) {
            if (repository.findById(jobId).isPresent()) {
                return CompletableFuture.completedFuture(new MessageEnqueueReceipt(jobId, true, now));
            }
            return CompletableFuture.failedFuture(exception);
        }
    }

    @Override
    public CompletionStage<MessageEnqueueReceipt> enqueue(MediaMessage message) {
        Objects.requireNonNull(message, "message must not be null");
        Instant now = clock.instant();
        String scopedDedup = message.deduplicationKey().map(value -> bindingId + ":" + value).orElse(null);
        UUID jobId = scopedDedup == null ? UUID.randomUUID() : UUID.nameUUIDFromBytes(
                (environment + ":" + botId + ":" + scopedDedup).getBytes(StandardCharsets.UTF_8));
        String payload;
        try {
            payload = mapper.writeValueAsString(new OutboundMediaPayload(
                    message.target().type(), message.target().id(), message.kind(),
                    message.mediaUrl().toString(), message.content().orElse(null),
                    message.replyMessageId().orElse(null), message.replyEventId().orElse(null),
                    message.messageSequence()));
        } catch (JsonProcessingException exception) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Unable to encode media message", exception));
        }
        try {
            repository.create(new NewOutboxJob(jobId, environment, botId, message.sourceEventId(),
                    OutboundMediaPayload.JOB_TYPE, Optional.ofNullable(scopedDedup), payload, now, now));
            return CompletableFuture.completedFuture(new MessageEnqueueReceipt(jobId, false, now));
        } catch (RuntimeException exception) {
            if (repository.findById(jobId).isPresent()) {
                return CompletableFuture.completedFuture(new MessageEnqueueReceipt(jobId, true, now));
            }
            return CompletableFuture.failedFuture(exception);
        }
    }
}
