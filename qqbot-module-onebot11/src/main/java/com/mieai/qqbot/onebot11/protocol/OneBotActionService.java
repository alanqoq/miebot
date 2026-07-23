package com.mieai.qqbot.onebot11.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mieai.qqbot.client.QqMediaKind;
import com.mieai.qqbot.client.QqMediaMessageRequest;
import com.mieai.qqbot.client.QqMessageSendResult;
import com.mieai.qqbot.client.QqMessageTargetType;
import com.mieai.qqbot.client.QqOpenApiClient;
import com.mieai.qqbot.client.QqTextMessageRequest;
import com.mieai.qqbot.domain.bot.BotId;
import com.mieai.qqbot.persistence.bot.BotRepository;
import com.mieai.qqbot.runtime.openapi.BotOpenApiClientProvider;
import com.mieai.qqbot.runtime.supervisor.BotRuntimeState;
import com.mieai.qqbot.runtime.supervisor.BotSupervisor;
import com.mieai.qqbot.onebot11.mapping.OneBotEntityIdRepository;
import com.mieai.qqbot.onebot11.mapping.OneBotEntityMapping;
import com.mieai.qqbot.onebot11.mapping.OneBotEntityType;
import com.mieai.qqbot.onebot11.mapping.OneBotMessageRepository;
import com.mieai.qqbot.onebot11.mapping.OneBotStoredMessage;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** OneBot action dispatcher for the QQ official C2C/group compatibility subset. */
public final class OneBotActionService implements AutoCloseable {
    private static final int MAX_REQUEST_CHARACTERS = 1_048_576;
    private static final int MAX_INLINE_MEDIA_BYTES = 256 * 1024 * 1024;
    private static final long RATE_LIMIT_INTERVAL_MS = 500L;

    private final ObjectMapper objectMapper;
    private final BotOpenApiClientProvider clients;
    private final BotRepository bots;
    private final BotSupervisor supervisor;
    private final OneBotEntityIdRepository entityIds;
    private final OneBotMessageRepository messages;
    private final OneBotMessageCodec messageCodec;
    private final OneBotMediaCache mediaCache;
    private final OneBotEventMapper eventMapper;
    private final ExecutorService actions = Executors.newFixedThreadPool(4,
            runnable -> daemon(runnable, "onebot11-action"));
    private final ScheduledExecutorService rateLimited = Executors.newSingleThreadScheduledExecutor(
            runnable -> daemon(runnable, "onebot11-rate-limited"));
    private final AtomicLong nextRateLimitedAt = new AtomicLong();
    private final Map<String, AtomicInteger> messageSequences = new ConcurrentHashMap<>();
    private volatile Consumer<BotId> restartHandler = ignored -> {};

    public OneBotActionService(
            ObjectMapper objectMapper,
            BotOpenApiClientProvider clients,
            BotRepository bots,
            BotSupervisor supervisor,
            OneBotEntityIdRepository entityIds,
            OneBotMessageRepository messages,
            OneBotMessageCodec messageCodec,
            OneBotMediaCache mediaCache,
            OneBotEventMapper eventMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.clients = Objects.requireNonNull(clients, "clients must not be null");
        this.bots = Objects.requireNonNull(bots, "bots must not be null");
        this.supervisor = Objects.requireNonNull(supervisor, "supervisor must not be null");
        this.entityIds = Objects.requireNonNull(entityIds, "entityIds must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.messageCodec = Objects.requireNonNull(messageCodec, "messageCodec must not be null");
        this.mediaCache = Objects.requireNonNull(mediaCache, "mediaCache must not be null");
        this.eventMapper = Objects.requireNonNull(eventMapper, "eventMapper must not be null");
    }

    public void setRestartHandler(Consumer<BotId> handler) {
        restartHandler = Objects.requireNonNull(handler, "handler must not be null");
    }

    public CompletionStage<String> handle(BotId botId, String payload) {
        Objects.requireNonNull(botId, "botId must not be null");
        if (payload == null || payload.length() > MAX_REQUEST_CHARACTERS) {
            return CompletableFuture.completedFuture(encode(failed(null, 1400, "Invalid request")));
        }
        final ObjectNode request;
        try {
            JsonNode decoded = objectMapper.readTree(payload);
            if (!(decoded instanceof ObjectNode object)) {
                throw new IllegalArgumentException("request must be a JSON object");
            }
            request = object;
        } catch (Exception exception) {
            return CompletableFuture.completedFuture(encode(failed(null, 1400, "Invalid JSON")));
        }

        JsonNode echo = request.has("echo") ? request.get("echo") : null;
        String requestedAction = request.path("action").isTextual()
                ? request.path("action").textValue() : null;
        if (requestedAction == null || requestedAction.isBlank()) {
            return CompletableFuture.completedFuture(
                    encode(failed(echo, 1400, "action is required")));
        }
        boolean asynchronous = requestedAction.endsWith("_async");
        boolean limited = requestedAction.endsWith("_rate_limited");
        String action = asynchronous
                ? requestedAction.substring(0, requestedAction.length() - "_async".length())
                : limited
                        ? requestedAction.substring(0,
                                requestedAction.length() - "_rate_limited".length())
                        : requestedAction;
        JsonNode params = request.path("params");
        if (params.isMissingNode() || params.isNull()) {
            params = objectMapper.createObjectNode();
        }
        if (!params.isObject()) {
            return CompletableFuture.completedFuture(
                    encode(failed(echo, 1400, "params must be an object")));
        }

        JsonNode actionParams = params;
        if (!isSupportedAction(action)) {
            return CompletableFuture.completedFuture(
                    encode(failed(echo, 1404, "Unsupported action")));
        }
        if (asynchronous || limited || action.equals("set_restart")) {
            Runnable operation = () -> executeSafely(botId, action, actionParams);
            if (limited) {
                scheduleRateLimited(operation);
            } else {
                actions.execute(operation);
            }
            return CompletableFuture.completedFuture(encode(async(echo)));
        }
        return CompletableFuture.supplyAsync(
                () -> encode(execute(botId, action, actionParams, echo)), actions);
    }

    private ObjectNode execute(BotId botId, String action, JsonNode params, JsonNode echo) {
        try {
            JsonNode data = switch (action) {
                case "send_private_msg" -> send(botId, QqMessageTargetType.C2C, params);
                case "send_group_msg" -> send(botId, QqMessageTargetType.GROUP, params);
                case "send_msg" -> sendInferred(botId, params);
                case "delete_msg" -> deleteMessage(botId, params);
                case "get_msg" -> getMessage(botId, params);
                case "get_login_info" -> loginInfo(botId);
                case "get_image" -> getImage(botId, params);
                case "get_record" -> getRecord(botId, params);
                case "can_send_image", "can_send_record" -> objectMapper.createObjectNode().put("yes", true);
                case "get_status" -> status(botId);
                case "get_version_info" -> versionInfo();
                case "set_restart" -> restart(botId);
                case "clean_cache" -> cleanCache(botId);
                default -> throw new UnsupportedActionException();
            };
            return ok(echo, data);
        } catch (UnsupportedActionException exception) {
            return failed(echo, 1404, "Unsupported action");
        } catch (IllegalArgumentException exception) {
            return failed(echo, 1400, safeMessage(exception, "Invalid parameters"));
        } catch (RuntimeException exception) {
            return failed(echo, 1200, "QQ operation failed");
        }
    }

    private void executeSafely(BotId botId, String action, JsonNode params) {
        execute(botId, action, params, null);
    }

    private static boolean isSupportedAction(String action) {
        return switch (action) {
            case "send_private_msg", "send_group_msg", "send_msg",
                    "delete_msg", "get_msg", "get_login_info", "get_image",
                    "get_record", "can_send_image", "can_send_record", "get_status",
                    "get_version_info", "set_restart", "clean_cache" -> true;
            default -> false;
        };
    }

    private JsonNode sendInferred(BotId botId, JsonNode params) {
        String type = optionalText(params, "message_type");
        if (type == null) {
            type = params.hasNonNull("group_id") ? "group"
                    : params.hasNonNull("user_id") ? "private" : null;
        }
        return switch (type == null ? "" : type) {
            case "private" -> send(botId, QqMessageTargetType.C2C, params);
            case "group" -> send(botId, QqMessageTargetType.GROUP, params);
            default -> throw new IllegalArgumentException("message_type is invalid");
        };
    }

    private JsonNode send(BotId botId, QqMessageTargetType targetType, JsonNode params) {
        boolean group = targetType == QqMessageTargetType.GROUP;
        long targetAlias = requiredLong(params, group ? "group_id" : "user_id");
        OneBotEntityMapping target = entityIds.require(botId, targetAlias,
                group ? OneBotEntityType.GROUP : OneBotEntityType.USER);
        if (!group && !target.scopeId().isEmpty()) {
            throw new IllegalArgumentException("A group-scoped user cannot be used for private messages");
        }
        JsonNode messageNode = params.get("message");
        if (messageNode == null) {
            throw new IllegalArgumentException("message is required");
        }
        List<OneBotSegment> segments = messageCodec.parse(
                messageNode, optionalBoolean(params, "auto_escape", false));
        CompiledMessage compiled = compile(botId, targetType, target.rawId(), segments);
        QqOpenApiClient client = clients.clientFor(botId);
        int sequence = nextSequence(botId, targetType, target.rawId());
        QqMessageSendResult sent;
        if (compiled.media() == null) {
            if (compiled.content().isBlank()) {
                throw new IllegalArgumentException("message has no sendable content");
            }
            sent = await(client.sendText(new QqTextMessageRequest(
                    targetType, target.rawId(), compiled.content(), compiled.replyMessageId(),
                    Optional.empty(), sequence)));
        } else {
            QqMediaMessageRequest mediaRequest = new QqMediaMessageRequest(
                    targetType,
                    target.rawId(),
                    compiled.media().kind(),
                    compiled.media().uri(),
                    compiled.content().isBlank() ? Optional.empty() : Optional.of(compiled.content()),
                    compiled.replyMessageId(),
                    Optional.empty(),
                    sequence);
            sent = compiled.media().bytes() == null
                    ? await(client.sendMediaBounded(mediaRequest))
                    : await(client.sendMedia(mediaRequest, compiled.media().bytes()));
        }
        if (sent == null || sent.id() == null || sent.id().isBlank()) {
            throw new IllegalStateException("QQ response has no message ID");
        }
        long selfId = eventMapper.selfId(botId);
        String nickname = bots.findById(botId).orElseThrow().definition().displayName();
        ObjectNode sender = objectMapper.createObjectNode();
        sender.put("user_id", selfId);
        sender.put("nickname", nickname);
        sender.put("sex", "unknown");
        sender.put("age", 0);
        int oneBotMessageId = messages.record(
                botId,
                sent.id(),
                targetType,
                target.rawId(),
                OneBotStoredMessage.Direction.OUTGOING,
                group ? "group" : "private",
                parseTime(sent.timestamp()),
                selfId,
                encode(messageCodec.toArray(segments)),
                encode(sender));
        return objectMapper.createObjectNode().put("message_id", oneBotMessageId);
    }

    private CompiledMessage compile(
            BotId botId, QqMessageTargetType targetType, String targetRawId,
            List<OneBotSegment> segments) {
        StringBuilder content = new StringBuilder();
        OutboundMedia media = null;
        Optional<String> reply = Optional.empty();
        for (OneBotSegment segment : segments) {
            switch (segment.type()) {
                case "text" -> content.append(segment.data().getOrDefault("text", ""));
                case "face" -> content.append("<emoji:")
                        .append(requiredData(segment, "id")).append('>');
                case "at" -> {
                    if (targetType != QqMessageTargetType.GROUP) {
                        throw new IllegalArgumentException("at is only supported in group messages");
                    }
                    long alias = parsePositiveLong(requiredData(segment, "qq"), "at qq");
                    OneBotEntityMapping user = entityIds.require(botId, alias, OneBotEntityType.USER);
                    if (!user.scopeId().equals(targetRawId)) {
                        throw new IllegalArgumentException("at user is not mapped in this group");
                    }
                    content.append("<@").append(user.rawId()).append('>');
                }
                case "reply" -> {
                    if (reply.isPresent()) {
                        throw new IllegalArgumentException("Only one reply segment is supported");
                    }
                    int id = Math.toIntExact(parsePositiveLong(requiredData(segment, "id"), "reply id"));
                    OneBotStoredMessage referenced = messages.find(botId, id)
                            .orElseThrow(() -> new IllegalArgumentException("Reply message is unknown"));
                    if (referenced.targetType() != targetType
                            || !referenced.targetRawId().equals(targetRawId)) {
                        throw new IllegalArgumentException("Reply message belongs to another conversation");
                    }
                    reply = Optional.of(referenced.officialMessageId());
                }
                case "image", "record", "video" -> {
                    if (media != null) {
                        throw new IllegalArgumentException("Only one media segment is supported per message");
                    }
                    media = outboundMedia(segment);
                }
                default -> throw new IllegalArgumentException(
                        "Unsupported message segment: " + segment.type());
            }
        }
        return new CompiledMessage(content.toString(), media, reply);
    }

    private OutboundMedia outboundMedia(OneBotSegment segment) {
        String file = segment.data().get("file");
        if (file == null || file.isBlank()) {
            file = segment.data().get("url");
        }
        if (file == null || file.isBlank()) {
            throw new IllegalArgumentException("media file is required");
        }
        QqMediaKind kind = switch (segment.type()) {
            case "image" -> QqMediaKind.IMAGE;
            case "record" -> QqMediaKind.AUDIO;
            case "video" -> QqMediaKind.VIDEO;
            default -> throw new IllegalArgumentException("media type is invalid");
        };
        if (file.startsWith("base64://")) {
            byte[] bytes;
            try {
                bytes = Base64.getDecoder().decode(file.substring("base64://".length()));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("media base64 is invalid", exception);
            }
            if (bytes.length == 0 || bytes.length > MAX_INLINE_MEDIA_BYTES) {
                throw new IllegalArgumentException("media base64 size is invalid");
            }
            return new OutboundMedia(kind, URI.create("https://onebot.invalid/media"), bytes);
        }
        URI uri;
        try {
            uri = URI.create(file);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("media file must be HTTPS or base64", exception);
        }
        if (!uri.isAbsolute() || !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("media file must be HTTPS or base64");
        }
        return new OutboundMedia(kind, uri, null);
    }

    private JsonNode deleteMessage(BotId botId, JsonNode params) {
        int messageId = Math.toIntExact(requiredLong(params, "message_id"));
        OneBotStoredMessage message = messages.find(botId, messageId)
                .orElseThrow(() -> new IllegalArgumentException("message_id is unknown"));
        await(clients.clientFor(botId).recallMessage(
                message.targetType(), message.targetRawId(), message.officialMessageId(), false));
        return NullNode.getInstance();
    }

    private JsonNode getMessage(BotId botId, JsonNode params) {
        int messageId = Math.toIntExact(requiredLong(params, "message_id"));
        OneBotStoredMessage message = messages.find(botId, messageId)
                .orElseThrow(() -> new IllegalArgumentException("message_id is unknown"));
        ObjectNode data = objectMapper.createObjectNode();
        data.put("time", message.eventTime());
        data.put("message_type", message.messageType());
        data.put("message_id", message.messageId());
        data.put("real_id", message.messageId());
        data.set("sender", decode(message.senderJson()));
        data.set("message", decode(message.messageJson()));
        if (message.targetType() == QqMessageTargetType.GROUP) {
            data.put("group_id", entityIds.aliasFor(
                    botId, OneBotEntityType.GROUP, "", message.targetRawId()));
        }
        return data;
    }

    private JsonNode loginInfo(BotId botId) {
        var bot = bots.findById(botId)
                .orElseThrow(() -> new IllegalArgumentException("Bot does not exist"));
        ObjectNode data = objectMapper.createObjectNode();
        data.put("user_id", eventMapper.selfId(botId));
        data.put("nickname", bot.definition().displayName());
        return data;
    }

    private JsonNode getImage(BotId botId, JsonNode params) {
        Path path = mediaCache.image(botId, requiredText(params, "file"));
        return objectMapper.createObjectNode().put("file", path.toString());
    }

    private JsonNode getRecord(BotId botId, JsonNode params) {
        Path path = mediaCache.record(botId, requiredText(params, "file"),
                requiredText(params, "out_format"));
        return objectMapper.createObjectNode().put("file", path.toString());
    }

    private JsonNode status(BotId botId) {
        boolean online = supervisor.status(botId)
                .map(status -> status.state() == BotRuntimeState.ONLINE).orElse(false);
        ObjectNode data = objectMapper.createObjectNode();
        data.put("online", online);
        data.put("good", online);
        return data;
    }

    private JsonNode versionInfo() {
        ObjectNode data = objectMapper.createObjectNode();
        data.put("app_name", "mirai-qqbot-onebot11");
        data.put("app_version", "0.3.0");
        data.put("protocol_version", "v11");
        data.put("compatibility", "qq-official-c2c-group-subset");
        return data;
    }

    private JsonNode restart(BotId botId) {
        restartHandler.accept(botId);
        return NullNode.getInstance();
    }

    private JsonNode cleanCache(BotId botId) {
        mediaCache.clean(botId);
        return NullNode.getInstance();
    }

    private void scheduleRateLimited(Runnable operation) {
        long now = System.currentTimeMillis();
        long scheduled = nextRateLimitedAt.updateAndGet(previous ->
                Math.max(now, previous) + RATE_LIMIT_INTERVAL_MS);
        rateLimited.schedule(operation, Math.max(0L, scheduled - now), TimeUnit.MILLISECONDS);
    }

    private ObjectNode ok(JsonNode echo, JsonNode data) {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("status", "ok");
        result.put("retcode", 0);
        result.set("data", data == null ? NullNode.getInstance() : data);
        setEcho(result, echo);
        return result;
    }

    private ObjectNode async(JsonNode echo) {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("status", "async");
        result.put("retcode", 1);
        result.set("data", NullNode.getInstance());
        setEcho(result, echo);
        return result;
    }

    private ObjectNode failed(JsonNode echo, int retcode, String message) {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("status", "failed");
        result.put("retcode", retcode);
        result.set("data", NullNode.getInstance());
        result.put("message", message);
        result.put("wording", message);
        setEcho(result, echo);
        return result;
    }

    private static void setEcho(ObjectNode result, JsonNode echo) {
        if (echo != null) {
            result.set("echo", echo.deepCopy());
        }
    }

    private JsonNode decode(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Stored OneBot JSON is invalid", exception);
        }
    }

    private String encode(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to encode OneBot JSON", exception);
        }
    }

    private static <T> T await(CompletionStage<T> stage) {
        try {
            return stage.toCompletableFuture().get(30, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("QQ operation was interrupted", exception);
        } catch (java.util.concurrent.ExecutionException
                | java.util.concurrent.TimeoutException exception) {
            throw new IllegalStateException("QQ operation failed", exception);
        }
    }

    private int nextSequence(BotId botId, QqMessageTargetType type, String targetId) {
        AtomicInteger sequence = messageSequences.computeIfAbsent(
                botId + ":" + type + ":" + targetId, ignored -> new AtomicInteger());
        return sequence.updateAndGet(previous -> previous == Integer.MAX_VALUE ? 1 : previous + 1);
    }

    private static long requiredLong(JsonNode params, String name) {
        JsonNode value = params.get(name);
        if (value == null || value.isNull()) {
            throw new IllegalArgumentException(name + " is required");
        }
        if (value.isIntegralNumber()) {
            long result = value.longValue();
            if (result < 1L) throw new IllegalArgumentException(name + " must be positive");
            return result;
        }
        if (value.isTextual()) {
            return parsePositiveLong(value.textValue(), name);
        }
        throw new IllegalArgumentException(name + " must be a number");
    }

    private static long parsePositiveLong(String value, String name) {
        try {
            long result = Long.parseLong(value);
            if (result < 1L) throw new NumberFormatException();
            return result;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be a positive integer", exception);
        }
    }

    private static String requiredText(JsonNode params, String name) {
        String value = optionalText(params, name);
        if (value == null) throw new IllegalArgumentException(name + " is required");
        return value;
    }

    private static String optionalText(JsonNode params, String name) {
        JsonNode value = params.get(name);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException(name + " must be non-blank text");
        }
        return value.textValue();
    }

    private static boolean optionalBoolean(JsonNode params, String name, boolean defaultValue) {
        JsonNode value = params.get(name);
        if (value == null || value.isNull()) return defaultValue;
        if (value.isBoolean()) return value.booleanValue();
        if (value.isTextual() && (value.textValue().equals("true")
                || value.textValue().equals("false"))) {
            return Boolean.parseBoolean(value.textValue());
        }
        throw new IllegalArgumentException(name + " must be a boolean");
    }

    private static String requiredData(OneBotSegment segment, String name) {
        String value = segment.data().get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(segment.type() + " " + name + " is required");
        }
        return value;
    }

    private static long parseTime(String value) {
        if (value != null) {
            try {
                return Instant.parse(value).getEpochSecond();
            } catch (DateTimeParseException ignored) {
                // Fall back to the local receipt time.
            }
        }
        return Instant.now().getEpochSecond();
    }

    private static String safeMessage(RuntimeException exception, String fallback) {
        String message = exception.getMessage();
        return message == null || message.isBlank() || message.length() > 240 ? fallback : message;
    }

    private static Thread daemon(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    @Override
    public void close() {
        actions.shutdownNow();
        rateLimited.shutdownNow();
    }

    private record OutboundMedia(QqMediaKind kind, URI uri, byte[] bytes) {}

    private record CompiledMessage(
            String content, OutboundMedia media, Optional<String> replyMessageId) {}

    private static final class UnsupportedActionException extends RuntimeException {}
}
