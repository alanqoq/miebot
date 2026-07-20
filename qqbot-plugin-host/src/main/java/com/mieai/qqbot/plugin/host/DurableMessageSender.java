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
import com.mieai.qqbot.plugin.api.MediaService;
import com.mieai.qqbot.plugin.api.MediaUpload;
import com.mieai.qqbot.plugin.api.StagedMedia;
import com.mieai.qqbot.plugin.api.StagedMediaMessage;
import com.mieai.qqbot.plugin.api.TextMessage;
import com.mieai.qqbot.plugin.api.RichMessage;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

final class DurableMessageSender implements MessageSender, MediaService {
    private final UUID bindingId;
    private final BotId botId;
    private final BotEnvironment environment;
    private final OutboxRepository repository;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final BindingCapabilityGuard capabilityGuard;
    private final Optional<com.mieai.qqbot.client.MediaAssetStore> mediaStore;
    private final long maxMediaBytes;

    DurableMessageSender(UUID bindingId, BotId botId, BotEnvironment environment,
            OutboxRepository repository, ObjectMapper mapper, Clock clock) {
        this(bindingId, botId, environment, repository, mapper, clock, new BindingCapabilityGuard());
    }

    DurableMessageSender(UUID bindingId, BotId botId, BotEnvironment environment,
            OutboxRepository repository, ObjectMapper mapper, Clock clock,
            BindingCapabilityGuard capabilityGuard) {
        this(bindingId, botId, environment, repository, mapper, clock, capabilityGuard,
                null, com.mieai.qqbot.client.QqClientOptions.DEFAULT_MAX_MEDIA_BYTES);
    }

    DurableMessageSender(UUID bindingId, BotId botId, BotEnvironment environment,
            OutboxRepository repository, ObjectMapper mapper, Clock clock,
            BindingCapabilityGuard capabilityGuard,
            com.mieai.qqbot.client.MediaAssetStore mediaStore, long maxMediaBytes) {
        this.bindingId = Objects.requireNonNull(bindingId, "bindingId must not be null");
        this.botId = Objects.requireNonNull(botId, "botId must not be null");
        this.environment = Objects.requireNonNull(environment, "environment must not be null");
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.capabilityGuard = Objects.requireNonNull(capabilityGuard, "capabilityGuard must not be null");
        this.mediaStore = Optional.ofNullable(mediaStore);
        if (maxMediaBytes < 1L) throw new IllegalArgumentException("maxMediaBytes must be positive");
        this.maxMediaBytes = maxMediaBytes;
    }

    @Override
    public CompletionStage<MessageEnqueueReceipt> enqueue(TextMessage message) {
        Objects.requireNonNull(message, "message must not be null");
        try { capabilityGuard.requireActive(); }
        catch (RuntimeException exception) { return CompletableFuture.failedFuture(exception); }
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
            capabilityGuard.requireActive();
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
        try { capabilityGuard.requireActive(); }
        catch (RuntimeException exception) { return CompletableFuture.failedFuture(exception); }
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
            capabilityGuard.requireActive();
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

    @Override
    public CompletionStage<MessageEnqueueReceipt> enqueue(RichMessage message) {
        Objects.requireNonNull(message, "message must not be null");
        try { capabilityGuard.requireActive(); }
        catch (RuntimeException exception) { return CompletableFuture.failedFuture(exception); }
        Instant now = clock.instant();
        String scopedDedup = message.deduplicationKey().map(value -> bindingId + ":" + value).orElse(null);
        UUID jobId = scopedDedup == null ? UUID.randomUUID() : UUID.nameUUIDFromBytes(
                (environment + ":" + botId + ":" + scopedDedup).getBytes(StandardCharsets.UTF_8));
        String payload;
        try {
            payload = mapper.writeValueAsString(new OutboundRichPayload(
                    message.target().type(), message.target().id(), message.kind(), message.payload(),
                    message.replyMessageId().orElse(null), message.replyEventId().orElse(null),
                    message.messageSequence()));
        } catch (JsonProcessingException exception) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Unable to encode rich message", exception));
        }
        try {
            capabilityGuard.requireActive();
            repository.create(new NewOutboxJob(jobId, environment, botId, message.sourceEventId(),
                    OutboundRichPayload.JOB_TYPE, Optional.ofNullable(scopedDedup), payload, now, now));
            return CompletableFuture.completedFuture(new MessageEnqueueReceipt(jobId, false, now));
        } catch (RuntimeException exception) {
            if (repository.findById(jobId).isPresent()) {
                return CompletableFuture.completedFuture(new MessageEnqueueReceipt(jobId, true, now));
            }
            return CompletableFuture.failedFuture(exception);
        }
    }

    @Override
    public CompletionStage<StagedMedia> stage(MediaUpload upload) {
        Objects.requireNonNull(upload, "upload must not be null");
        try { capabilityGuard.requireActive(); }
        catch (RuntimeException exception) { return CompletableFuture.failedFuture(exception); }
        if (mediaStore.isEmpty()) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException("media staging is not configured"));
        }
        return CompletableFuture.supplyAsync(() -> {
            com.mieai.qqbot.client.MediaAsset asset = mediaStore.orElseThrow().stage(
                    botId, com.mieai.qqbot.client.QqMediaKind.valueOf(upload.kind().name()),
                    upload.fileName(), upload.contentType(), new ByteArrayInputStream(upload.data()), maxMediaBytes);
            try {
                capabilityGuard.requireActive();
                return new StagedMedia(asset.id(), upload.kind(), asset.fileName(), asset.sizeBytes());
            } catch (RuntimeException exception) {
                mediaStore.orElseThrow().delete(asset);
                throw exception;
            }
        });
    }

    @Override
    public CompletionStage<MessageEnqueueReceipt> enqueue(StagedMediaMessage message) {
        Objects.requireNonNull(message, "message must not be null");
        try { capabilityGuard.requireActive(); }
        catch (RuntimeException exception) { return CompletableFuture.failedFuture(exception); }
        if (mediaStore.isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("staged media is not available"));
        }
        com.mieai.qqbot.client.MediaAsset asset = mediaStore.orElseThrow()
                .find(botId, message.media().id()).orElse(null);
        if (asset == null || asset.kind() != com.mieai.qqbot.client.QqMediaKind.valueOf(message.media().kind().name())
                || asset.sizeBytes() != message.media().sizeBytes()
                || asset.sizeBytes() > maxMediaBytes) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("staged media metadata does not match the stored asset"));
        }
        Instant now = clock.instant();
        String scopedDedup = message.deduplicationKey().map(value -> bindingId + ":" + value).orElse(null);
        UUID jobId = scopedDedup == null ? UUID.randomUUID() : UUID.nameUUIDFromBytes(
                (environment + ":" + botId + ":" + scopedDedup).getBytes(StandardCharsets.UTF_8));
        String payload;
        try {
            payload = mapper.writeValueAsString(new OutboundMediaPayload(
                    message.target().type(), message.target().id(),
                    com.mieai.qqbot.plugin.api.MediaKind.valueOf(message.media().kind().name()),
                    "https://asset.invalid/" + message.media().id(), message.content().orElse(null),
                    message.replyMessageId().orElse(null), message.replyEventId().orElse(null),
                    message.messageSequence(), message.media().id()));
        } catch (JsonProcessingException exception) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Unable to encode staged media", exception));
        }
        try {
            capabilityGuard.requireActive();
            repository.create(new NewOutboxJob(jobId, environment, botId, message.sourceEventId(),
                    OutboundMediaPayload.JOB_TYPE, Optional.ofNullable(scopedDedup), payload, now, now));
            return CompletableFuture.completedFuture(new MessageEnqueueReceipt(jobId, false, now));
        } catch (RuntimeException exception) {
            if (repository.findById(jobId).isPresent()) return CompletableFuture.completedFuture(new MessageEnqueueReceipt(jobId, true, now));
            return CompletableFuture.failedFuture(exception);
        }
    }
}
