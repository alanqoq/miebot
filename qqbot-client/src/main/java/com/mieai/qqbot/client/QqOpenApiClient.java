package com.mieai.qqbot.client;

import com.mieai.qqbot.domain.bot.BotProfile;
import com.mieai.qqbot.protocol.gateway.GatewayUrlResponse;
import com.mieai.qqbot.protocol.json.JsonCodecs;
import com.mieai.qqbot.protocol.user.CurrentBotUser;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/** Asynchronous client for stable, bot-scoped QQ OpenAPI discovery operations. */
public final class QqOpenApiClient {
    private static final String GATEWAY_PATH = "/gateway";
    private static final String GATEWAY_BOT_PATH = "/gateway/bot";
    private static final String CURRENT_BOT_PATH = "/users/@me";

    private final QqClientOptions options;
    private final TokenProvider tokenProvider;
    private final JdkQqHttpTransport transport;

    public QqOpenApiClient(QqClientOptions options, TokenProvider tokenProvider) {
        this.options = Objects.requireNonNull(options, "options must not be null");
        this.tokenProvider = Objects.requireNonNull(tokenProvider, "tokenProvider must not be null");
        transport = new JdkQqHttpTransport(JsonCodecs.defaultCodec(), options.requestTimeout());
    }

    public CompletionStage<URI> getGateway() {
        URI endpoint = endpoint(GATEWAY_PATH);
        return authorizedGet(endpoint, GatewayUrlResponse.class)
                .thenApply(response -> parseGatewayUri(response.url(), endpoint));
    }

    public CompletionStage<GatewayBotInfo> getGatewayBot() {
        URI endpoint = endpoint(GATEWAY_BOT_PATH);
        return authorizedGet(endpoint, GatewayBotProtocolResponse.class)
                .thenApply(response -> toGatewayBotInfo(response, endpoint));
    }

    public CompletionStage<BotProfile> getCurrentBot() {
        URI endpoint = endpoint(CURRENT_BOT_PATH);
        return authorizedGet(endpoint, CurrentBotUser.class)
                .thenApply(response -> toBotProfile(response, endpoint));
    }

    public CompletionStage<QqMessageSendResult> sendText(QqTextMessageRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        URI endpoint = messageEndpoint(request.targetType(), request.targetId());
        TextMessagePayload body = new TextMessagePayload(
                request.content(), request.replyMessageId().orElse(null), request.replyEventId().orElse(null),
                request.messageSequence(), request.targetType() == QqMessageTargetType.CHANNEL
                        || request.targetType() == QqMessageTargetType.DIRECT ? null : 0);
        return tokenProvider.getAccessToken()
                .thenCompose(token -> transport.postAuthorizedJson(endpoint, token, body, QqMessageSendResult.class));
    }

    public CompletionStage<QqMessageSendResult> sendMedia(QqMediaMessageRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        if (request.targetType() == QqMessageTargetType.C2C
                || request.targetType() == QqMessageTargetType.GROUP) {
            URI uploadEndpoint = mediaUploadEndpoint(request.targetType(), request.targetId());
            MediaUploadPayload upload = new MediaUploadPayload(
                    request.mediaKind().fileType(), request.mediaUrl().toString(), false);
            return tokenProvider.getAccessToken().thenCompose(token ->
                    transport.postAuthorizedJson(uploadEndpoint, token, upload, QqMediaUploadResult.class)
                            .thenCompose(result -> {
                                if (result.fileInfo() == null || result.fileInfo().isBlank()) {
                                    return java.util.concurrent.CompletableFuture.failedFuture(
                                            QqClientException.protocol(uploadEndpoint,
                                                    new IllegalArgumentException("file_info is missing")));
                                }
                                MediaMessagePayload message = new MediaMessagePayload(
                                        request.content().orElse(null), new MediaReference(result.fileInfo()),
                                        request.replyMessageId().orElse(null), request.replyEventId().orElse(null),
                                        request.messageSequence(), 7);
                                return transport.postAuthorizedJson(
                                        messageEndpoint(request.targetType(), request.targetId()), token,
                                        message, QqMessageSendResult.class);
                            }));
        }
        if (request.mediaKind() != QqMediaKind.IMAGE) {
            return java.util.concurrent.CompletableFuture.failedFuture(
                    new IllegalArgumentException("Channel and direct messages only support image URLs"));
        }
        ChannelImagePayload payload = new ChannelImagePayload(
                request.content().orElse(null), request.mediaUrl().toString(),
                request.replyMessageId().orElse(null), request.replyEventId().orElse(null));
        URI endpoint = messageEndpoint(request.targetType(), request.targetId());
        return tokenProvider.getAccessToken().thenCompose(token ->
                transport.postAuthorizedJson(endpoint, token, payload, QqMessageSendResult.class));
    }

    private <T> CompletionStage<T> authorizedGet(URI endpoint, Class<T> responseType) {
        return tokenProvider
                .getAccessToken()
                .thenCompose(token -> transport.getJson(endpoint, token, responseType));
    }

    private GatewayBotInfo toGatewayBotInfo(GatewayBotProtocolResponse response, URI endpoint) {
        try {
            SessionStartLimitProtocolResponse limit = Objects.requireNonNull(
                    response.session_start_limit(), "session_start_limit must not be null");
            return new GatewayBotInfo(
                    parseGatewayUri(response.url(), endpoint),
                    response.shards(),
                    new SessionStartLimit(
                            limit.total(),
                            limit.remaining(),
                            Duration.ofMillis(limit.reset_after()),
                            limit.max_concurrency()));
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw QqClientException.protocol(endpoint, exception);
        }
    }

    private BotProfile toBotProfile(CurrentBotUser response, URI endpoint) {
        try {
            if (response.avatar() == null || response.avatar().isBlank()) {
                return new BotProfile(response.id(), response.username());
            }
            return new BotProfile(response.id(), response.username(), URI.create(response.avatar()));
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw QqClientException.protocol(endpoint, exception);
        }
    }

    private URI parseGatewayUri(String value, URI endpoint) {
        try {
            URI uri = URI.create(Objects.requireNonNull(value, "url must not be null"));
            // Reuse the stable model's WSS validation without exposing transport DTOs.
            new GatewayBotInfo(uri, 1, new SessionStartLimit(1, 1, Duration.ZERO, 1));
            return uri;
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw QqClientException.protocol(endpoint, exception);
        }
    }

    private URI endpoint(String path) {
        return options.openApiBaseUri().resolve(path);
    }

    private URI messageEndpoint(QqMessageTargetType type, String id) {
        String encoded = URLEncoder.encode(id, StandardCharsets.UTF_8).replace("+", "%20");
        return switch (type) {
            case C2C -> endpoint("v2/users/" + encoded + "/messages");
            case GROUP -> endpoint("v2/groups/" + encoded + "/messages");
            case CHANNEL -> endpoint("channels/" + encoded + "/messages");
            case DIRECT -> endpoint("dms/" + encoded + "/messages");
        };
    }

    private URI mediaUploadEndpoint(QqMessageTargetType type, String id) {
        String encoded = URLEncoder.encode(id, StandardCharsets.UTF_8).replace("+", "%20");
        return switch (type) {
            case C2C -> endpoint("v2/users/" + encoded + "/files");
            case GROUP -> endpoint("v2/groups/" + encoded + "/files");
            case CHANNEL, DIRECT -> throw new IllegalArgumentException("This target does not use media pre-upload");
        };
    }

    private record TextMessagePayload(
            String content,
            @com.fasterxml.jackson.annotation.JsonProperty("msg_id") String messageId,
            @com.fasterxml.jackson.annotation.JsonProperty("event_id") String eventId,
            @com.fasterxml.jackson.annotation.JsonProperty("msg_seq") Integer messageSequence,
            @com.fasterxml.jackson.annotation.JsonProperty("msg_type") Integer messageType) {}

    private record MediaUploadPayload(
            @com.fasterxml.jackson.annotation.JsonProperty("file_type") int fileType,
            String url,
            @com.fasterxml.jackson.annotation.JsonProperty("srv_send_msg") boolean serverSendMessage) {}

    private record MediaReference(
            @com.fasterxml.jackson.annotation.JsonProperty("file_info") String fileInfo) {}

    private record MediaMessagePayload(
            String content,
            MediaReference media,
            @com.fasterxml.jackson.annotation.JsonProperty("msg_id") String messageId,
            @com.fasterxml.jackson.annotation.JsonProperty("event_id") String eventId,
            @com.fasterxml.jackson.annotation.JsonProperty("msg_seq") int messageSequence,
            @com.fasterxml.jackson.annotation.JsonProperty("msg_type") int messageType) {}

    private record ChannelImagePayload(
            String content,
            String image,
            @com.fasterxml.jackson.annotation.JsonProperty("msg_id") String messageId,
            @com.fasterxml.jackson.annotation.JsonProperty("event_id") String eventId) {}
}
