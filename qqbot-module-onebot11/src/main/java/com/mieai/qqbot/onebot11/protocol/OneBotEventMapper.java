package com.mieai.qqbot.onebot11.protocol;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mieai.qqbot.client.QqMessageTargetType;
import com.mieai.qqbot.domain.bot.BotId;
import com.mieai.qqbot.persistence.bot.BotRepository;
import com.mieai.qqbot.protocol.event.QqEventModels;
import com.mieai.qqbot.runtime.event.BotGatewayEvent;
import com.mieai.qqbot.onebot11.mapping.OneBotEntityIdRepository;
import com.mieai.qqbot.onebot11.mapping.OneBotEntityType;
import com.mieai.qqbot.onebot11.mapping.OneBotMessageRepository;
import com.mieai.qqbot.onebot11.mapping.OneBotStoredMessage;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Converts only QQ C2C and ordinary-group events into standard OneBot 11 events. */
public final class OneBotEventMapper {
    private final ObjectMapper objectMapper;
    private final BotRepository bots;
    private final OneBotEntityIdRepository entityIds;
    private final OneBotMessageRepository messages;
    private final OneBotMessageCodec messageCodec;
    private final Clock clock;

    public OneBotEventMapper(
            ObjectMapper objectMapper,
            BotRepository bots,
            OneBotEntityIdRepository entityIds,
            OneBotMessageRepository messages,
            OneBotMessageCodec messageCodec) {
        this(objectMapper, bots, entityIds, messages, messageCodec, Clock.systemUTC());
    }

    OneBotEventMapper(
            ObjectMapper objectMapper,
            BotRepository bots,
            OneBotEntityIdRepository entityIds,
            OneBotMessageRepository messages,
            OneBotMessageCodec messageCodec,
            Clock clock) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.bots = Objects.requireNonNull(bots, "bots must not be null");
        this.entityIds = Objects.requireNonNull(entityIds, "entityIds must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.messageCodec = Objects.requireNonNull(messageCodec, "messageCodec must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public Optional<ObjectNode> map(BotGatewayEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        return switch (event.dispatch().eventType()) {
            case "C2C_MESSAGE_CREATE" -> mapMessage(event, false);
            case "GROUP_AT_MESSAGE_CREATE", "GROUP_MESSAGE_CREATE" -> mapMessage(event, true);
            case "FRIEND_ADD" -> mapFriendAdd(event);
            case "GROUP_ADD_ROBOT", "GROUP_DEL_ROBOT",
                    "GROUP_MEMBER_ADD", "GROUP_MEMBER_REMOVE" -> mapGroupLifecycle(event);
            default -> Optional.empty();
        };
    }

    public long selfId(BotId botId) {
        String appId = bots.findById(botId)
                .orElseThrow(() -> new IllegalArgumentException("Bot does not exist"))
                .definition().appId().value();
        return entityIds.aliasFor(botId, OneBotEntityType.SELF, "", appId);
    }

    private Optional<ObjectNode> mapMessage(BotGatewayEvent event, boolean group) {
        Optional<?> decoded = event.dispatch().decodeKnownEvent();
        if (decoded.isEmpty() || !(decoded.orElseThrow() instanceof QqEventModels.Message message)) {
            return Optional.empty();
        }
        String groupRawId = group ? normalized(message.groupOpenId()) : null;
        String userRawId = authorId(message.author(), group);
        String officialMessageId = normalized(message.id());
        if (userRawId == null || officialMessageId == null || group && groupRawId == null) {
            return Optional.empty();
        }

        BotId botId = event.botId();
        long selfId = selfId(botId);
        long userId = entityIds.aliasFor(botId, OneBotEntityType.USER,
                group ? groupRawId : "", userRawId);
        long groupId = group
                ? entityIds.aliasFor(botId, OneBotEntityType.GROUP, "", groupRawId) : 0L;
        List<OneBotSegment> segments = segments(botId, message);
        long eventTime = parseTime(message.timestamp(), event.receivedAt());
        ObjectNode sender = sender(message, userId, group);
        int messageId = messages.record(
                botId,
                officialMessageId,
                group ? QqMessageTargetType.GROUP : QqMessageTargetType.C2C,
                group ? groupRawId : userRawId,
                OneBotStoredMessage.Direction.INCOMING,
                group ? "group" : "private",
                eventTime,
                userId,
                encode(messageCodec.toArray(segments)),
                encode(sender));

        ObjectNode result = objectMapper.createObjectNode();
        result.put("time", eventTime);
        result.put("self_id", selfId);
        result.put("post_type", "message");
        result.put("message_type", group ? "group" : "private");
        result.put("sub_type", group ? "normal" : "friend");
        result.put("message_id", messageId);
        result.put("user_id", userId);
        if (group) {
            result.put("group_id", groupId);
        }
        result.set("message", messageCodec.toArray(segments));
        result.put("raw_message", messageCodec.toRawMessage(segments));
        result.put("font", 0);
        result.set("sender", sender);
        return Optional.of(result);
    }

    private Optional<ObjectNode> mapFriendAdd(BotGatewayEvent event) {
        Optional<?> decoded = event.dispatch().decodeKnownEvent();
        if (decoded.isEmpty()
                || !(decoded.orElseThrow() instanceof QqEventModels.UserLifecycle lifecycle)) {
            return Optional.empty();
        }
        String openId = normalized(lifecycle.openId());
        if (openId == null) return Optional.empty();
        long userId = entityIds.aliasFor(event.botId(), OneBotEntityType.USER, "",
                openId);
        ObjectNode result = baseNotice(event.botId(), epochSeconds(lifecycle.timestamp(), event.receivedAt()));
        result.put("notice_type", "friend_add");
        result.put("user_id", userId);
        return Optional.of(result);
    }

    private Optional<ObjectNode> mapGroupLifecycle(BotGatewayEvent event) {
        Optional<?> decoded = event.dispatch().decodeKnownEvent();
        if (decoded.isEmpty()
                || !(decoded.orElseThrow() instanceof QqEventModels.GroupLifecycle lifecycle)) {
            return Optional.empty();
        }
        String groupRawId = normalized(lifecycle.groupOpenId());
        if (groupRawId == null) {
            return Optional.empty();
        }
        String type = event.dispatch().eventType();
        boolean increase = type.endsWith("ADD_ROBOT") || type.endsWith("MEMBER_ADD");
        boolean robot = type.endsWith("ROBOT");
        String memberRawId = normalized(lifecycle.memberOpenId());
        if (!robot && memberRawId == null) {
            return Optional.empty();
        }
        long selfId = selfId(event.botId());
        long groupId = entityIds.aliasFor(event.botId(), OneBotEntityType.GROUP, "", groupRawId);
        long userId = robot ? selfId : entityIds.aliasFor(event.botId(), OneBotEntityType.USER,
                groupRawId, memberRawId);
        String operatorRawId = normalized(lifecycle.operatorMemberOpenId());
        long operatorId = operatorRawId == null ? userId
                : entityIds.aliasFor(event.botId(), OneBotEntityType.USER, groupRawId, operatorRawId);

        ObjectNode result = baseNotice(
                event.botId(), epochSeconds(lifecycle.timestamp(), event.receivedAt()));
        result.put("notice_type", increase ? "group_increase" : "group_decrease");
        result.put("sub_type", lifecycleSubtype(increase, robot, memberRawId, operatorRawId));
        result.put("group_id", groupId);
        result.put("operator_id", operatorId);
        result.put("user_id", userId);
        return Optional.of(result);
    }

    private ObjectNode baseNotice(BotId botId, long eventTime) {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("time", eventTime);
        result.put("self_id", selfId(botId));
        result.put("post_type", "notice");
        return result;
    }

    private List<OneBotSegment> segments(BotId botId, QqEventModels.Message message) {
        List<OneBotSegment> result = new ArrayList<>();
        if (message.messageReference() != null
                && normalized(message.messageReference().messageId()) != null) {
            messages.findByOfficial(botId, message.messageReference().messageId())
                    .ifPresent(reference -> result.add(new OneBotSegment(
                            "reply", Map.of("id", Integer.toString(reference.messageId())))));
        }
        if (message.content() != null && !message.content().isEmpty()) {
            result.add(OneBotMessageCodec.text(message.content()));
        }
        if (message.attachments() != null) {
            for (QqEventModels.Attachment attachment : message.attachments()) {
                String url = normalized(attachment.url());
                String type = attachmentType(attachment.contentType());
                if (url == null || type == null) {
                    continue;
                }
                Map<String, String> data = type.equals("image")
                        ? Map.of("file", url, "url", url)
                        : Map.of("file", url);
                result.add(new OneBotSegment(type, data));
            }
        }
        if (result.isEmpty()) {
            result.add(OneBotMessageCodec.text(""));
        }
        return result;
    }

    private ObjectNode sender(QqEventModels.Message message, long userId, boolean group) {
        ObjectNode sender = objectMapper.createObjectNode();
        sender.put("user_id", userId);
        String nickname = message.member() != null && normalized(message.member().nick()) != null
                ? message.member().nick()
                : message.author() != null && normalized(message.author().username()) != null
                        ? message.author().username() : "";
        sender.put("nickname", nickname);
        sender.put("sex", "unknown");
        sender.put("age", 0);
        if (group) {
            sender.put("card", nickname);
            sender.put("area", "");
            sender.put("level", "");
            sender.put("role", "member");
            sender.put("title", "");
        }
        return sender;
    }

    private String encode(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to encode OneBot message", exception);
        }
    }

    private static String authorId(QqEventModels.EventUser author, boolean group) {
        if (author == null) {
            return null;
        }
        String primary = group ? normalized(author.memberOpenId()) : normalized(author.userOpenId());
        if (primary != null) {
            return primary;
        }
        String secondary = group ? normalized(author.userOpenId()) : normalized(author.memberOpenId());
        return secondary != null ? secondary : normalized(author.id());
    }

    private static String attachmentType(String contentType) {
        if (contentType == null) return null;
        String value = contentType.toLowerCase(java.util.Locale.ROOT);
        if (value.startsWith("image/")) return "image";
        if (value.startsWith("audio/")) return "record";
        if (value.startsWith("video/")) return "video";
        return null;
    }

    private static String lifecycleSubtype(
            boolean increase, boolean robot, String memberId, String operatorId) {
        if (increase) {
            return operatorId != null && !operatorId.equals(memberId) ? "invite" : "approve";
        }
        if (robot) {
            return "kick_me";
        }
        return operatorId == null || operatorId.equals(memberId) ? "leave" : "kick";
    }

    private static long parseTime(String value, Instant fallback) {
        if (value != null) {
            try {
                return Instant.parse(value).getEpochSecond();
            } catch (DateTimeParseException ignored) {
                // Use durable receipt time for malformed or future timestamp formats.
            }
        }
        return fallback.getEpochSecond();
    }

    private static long epochSeconds(Long value, Instant fallback) {
        if (value == null || value < 0L) {
            return fallback.getEpochSecond();
        }
        return value > 10_000_000_000L ? value / 1_000L : value;
    }

    private static String normalized(String value) {
        if (value == null) return null;
        String result = value.strip();
        return result.isEmpty() ? null : result;
    }
}
