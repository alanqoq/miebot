package com.mieai.qqbot.admin.bot;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mieai.qqbot.client.MediaAssetStore;
import com.mieai.qqbot.client.MediaAsset;
import com.mieai.qqbot.client.QqMediaMessageRequest;
import com.mieai.qqbot.client.QqMessageTargetType;
import com.mieai.qqbot.client.QqRichMessageKind;
import com.mieai.qqbot.client.QqRichMessageRequest;
import com.mieai.qqbot.client.QqTextMessageRequest;
import com.mieai.qqbot.domain.bot.BotId;
import com.mieai.qqbot.persistence.bot.BotRepository;
import com.mieai.qqbot.persistence.bot.StoredBot;
import com.mieai.qqbot.persistence.outbox.NewOutboxJob;
import com.mieai.qqbot.persistence.outbox.OutboxRepository;
import com.mieai.qqbot.runtime.outbox.OutboundMediaPayload;
import com.mieai.qqbot.runtime.outbox.OutboundRichPayload;
import com.mieai.qqbot.runtime.outbox.OutboundTextPayload;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class BotMessageAdministrationService {
    private final BotRepository bots;
    private final OutboxRepository outbox;
    private final MediaAssetStore mediaStore;
    private final ObjectMapper mapper;
    private final Clock clock;

    @Autowired
    public BotMessageAdministrationService(BotRepository bots, OutboxRepository outbox,
            MediaAssetStore mediaStore, ObjectMapper mapper) {
        this(bots, outbox, mediaStore, mapper, Clock.systemUTC());
    }

    BotMessageAdministrationService(BotRepository bots, OutboxRepository outbox,
            MediaAssetStore mediaStore, ObjectMapper mapper, Clock clock) {
        this.bots = Objects.requireNonNull(bots, "bots must not be null");
        this.outbox = Objects.requireNonNull(outbox, "outbox must not be null");
        this.mediaStore = Objects.requireNonNull(mediaStore, "mediaStore must not be null");
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public BotMessageResponse send(String botId, SendBotMessageRequest request) {
        BotId id = BotId.parse(botId);
        StoredBot bot = bots.findById(id).orElseThrow(() -> new BotMessageAdministrationException(
                HttpStatus.NOT_FOUND, "BOT_NOT_FOUND", "Bot does not exist"));
        if (!bot.definition().enabled()) throw new BotMessageAdministrationException(
                HttpStatus.CONFLICT, "BOT_DISABLED", "Bot is disabled");
        Objects.requireNonNull(request, "request must not be null");
        Instant now = clock.instant();
        UUID jobId = UUID.randomUUID();
        String type;
        String payload;
        MediaAsset stagedAsset = null;
        try {
            switch (request.kind()) {
                case TEXT -> {
                    requireText(request.content());
                    new QqTextMessageRequest(request.targetType(), request.targetId(), request.content(),
                            Optional.ofNullable(request.replyMessageId()), Optional.ofNullable(request.replyEventId()),
                            request.messageSequence());
                    type = OutboundTextPayload.JOB_TYPE;
                    payload = mapper.writeValueAsString(new OutboundTextPayload(
                            request.targetType(), request.targetId(), request.content(),
                            request.replyMessageId(), request.replyEventId(), request.messageSequence()));
                }
                case MEDIA -> {
                    if (request.mediaKind() == null) throw invalid("mediaKind is required");
                    if ((request.targetType() == QqMessageTargetType.CHANNEL
                            || request.targetType() == QqMessageTargetType.DIRECT)
                            && request.mediaKind() != com.mieai.qqbot.client.QqMediaKind.IMAGE) {
                        throw invalid("Channel and direct messages only support image media");
                    }
                    UUID assetId = request.mediaAssetId();
                    String url = request.mediaUrl();
                    if (assetId == null && (url == null || url.isBlank())) throw invalid("mediaUrl or mediaAssetId is required");
                    if (assetId != null) {
                        MediaAsset asset = mediaStore.find(id, assetId)
                                .orElseThrow(() -> invalid("mediaAssetId is not available"));
                        stagedAsset = asset;
                        if (asset.sizeBytes() > bot.definition().maxMediaUploadBytes()) {
                            throw new MediaUploadTooLargeException(asset.sizeBytes(),
                                    bot.definition().maxMediaUploadBytes());
                        }
                        if (asset.kind() != request.mediaKind()) {
                            throw invalid("mediaAssetId does not match mediaKind");
                        }
                        url = "https://asset.invalid/" + assetId;
                    }
                    new QqMediaMessageRequest(request.targetType(), request.targetId(), request.mediaKind(),
                            URI.create(url), Optional.ofNullable(request.content()),
                            Optional.ofNullable(request.replyMessageId()), Optional.ofNullable(request.replyEventId()),
                            request.messageSequence());
                    type = OutboundMediaPayload.JOB_TYPE;
                    payload = mapper.writeValueAsString(new OutboundMediaPayload(
                            request.targetType(), request.targetId(), request.mediaKind(), url,
                            request.content(), request.replyMessageId(), request.replyEventId(),
                            request.messageSequence(), assetId));
                }
                default -> {
                    Map<String, Object> body = request.payload() == null ? Map.of() : request.payload();
                    if (body.isEmpty()) throw invalid("payload is required for rich messages");
                    new QqRichMessageRequest(request.targetType(), request.targetId(),
                            QqRichMessageKind.valueOf(request.kind().name()), body,
                            Optional.ofNullable(request.replyMessageId()), Optional.ofNullable(request.replyEventId()),
                            request.messageSequence());
                    type = OutboundRichPayload.JOB_TYPE;
                    payload = mapper.writeValueAsString(new OutboundRichPayload(
                            request.targetType(), request.targetId(),
                            QqRichMessageKind.valueOf(request.kind().name()), body,
                            request.replyMessageId(), request.replyEventId(), request.messageSequence()));
                }
            }
        } catch (JsonProcessingException exception) {
            deleteQuietly(stagedAsset);
            throw new BotMessageAdministrationException(HttpStatus.BAD_REQUEST, "INVALID_MESSAGE", "Message payload is invalid");
        } catch (IllegalArgumentException | NullPointerException exception) {
            deleteQuietly(stagedAsset);
            throw invalid(exception.getMessage() == null ? "Message payload is invalid" : exception.getMessage());
        } catch (RuntimeException exception) {
            deleteQuietly(stagedAsset);
            throw exception;
        }
        try {
            outbox.create(new NewOutboxJob(jobId, bot.definition().environment(), id, Optional.empty(),
                    type, Optional.empty(), payload, now, now));
        } catch (RuntimeException exception) {
            deleteQuietly(stagedAsset);
            throw exception;
        }
        return new BotMessageResponse(jobId, false, now);
    }

    private static void requireText(String value) {
        if (value == null || value.isBlank()) throw invalid("content is required");
    }

    private static BotMessageAdministrationException invalid(String message) {
        return new BotMessageAdministrationException(HttpStatus.BAD_REQUEST, "INVALID_MESSAGE", message);
    }

    private void deleteQuietly(MediaAsset asset) {
        if (asset == null) return;
        try {
            mediaStore.delete(asset);
        } catch (RuntimeException ignored) {
            // Preserve the request failure; the staged directory remains operator-recoverable.
        }
    }
}
