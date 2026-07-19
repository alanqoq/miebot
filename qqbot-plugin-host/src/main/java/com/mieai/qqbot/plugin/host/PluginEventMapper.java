package com.mieai.qqbot.plugin.host;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mieai.qqbot.persistence.inbox.InboxEvent;
import com.mieai.qqbot.plugin.api.InboundMessage;
import com.mieai.qqbot.plugin.api.MessageTarget;
import com.mieai.qqbot.plugin.api.MessageTargetType;
import com.mieai.qqbot.plugin.api.PluginEvent;
import java.util.Locale;
import java.util.Optional;

final class PluginEventMapper {
    private final ObjectMapper mapper;

    PluginEventMapper(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    PluginEvent map(InboxEvent event) {
        return new PluginEvent(event.id(), event.botId(), event.environment(), event.eventType(),
                event.platformEventId(), event.payload(), event.receivedAt(), message(event));
    }

    private Optional<InboundMessage> message(InboxEvent event) {
        try {
            JsonNode root = mapper.readTree(event.payload());
            JsonNode data = root.path("d");
            String type = event.eventType().toUpperCase(Locale.ROOT);
            MessageTarget target;
            if (type.contains("C2C_MESSAGE")) {
                String id = first(data.path("author"), "user_openid", "member_openid", "id");
                if (id == null) return Optional.empty();
                target = new MessageTarget(MessageTargetType.C2C, id);
            } else if (type.contains("GROUP") && type.contains("MESSAGE")) {
                String id = text(data, "group_openid");
                if (id == null) return Optional.empty();
                target = new MessageTarget(MessageTargetType.GROUP, id);
            } else if (type.contains("DIRECT_MESSAGE")) {
                String id = first(data, "guild_id", "src_guild_id");
                if (id == null) return Optional.empty();
                target = new MessageTarget(MessageTargetType.DIRECT, id);
            } else if (type.contains("MESSAGE")) {
                String id = text(data, "channel_id");
                if (id == null) return Optional.empty();
                target = new MessageTarget(MessageTargetType.CHANNEL, id);
            } else {
                return Optional.empty();
            }
            String messageId = first(data, "id", "msg_id", "message_id");
            String envelopeEventId = first(root, "id", "event_id");
            String authorId = first(data.path("author"), "user_openid", "member_openid", "id");
            String content = text(data, "content");
            return Optional.of(new InboundMessage(target, Optional.ofNullable(messageId),
                    Optional.ofNullable(envelopeEventId == null ? event.platformEventId() : envelopeEventId),
                    Optional.ofNullable(authorId), Optional.ofNullable(content)));
        } catch (RuntimeException | java.io.IOException ignored) {
            return Optional.empty();
        }
    }

    private static String first(JsonNode node, String... names) {
        for (String name : names) {
            String value = text(node, name);
            if (value != null) return value;
        }
        return null;
    }

    private static String text(JsonNode node, String name) {
        JsonNode value = node.path(name);
        if (!value.isTextual()) return null;
        String text = value.textValue();
        return text == null || text.isBlank() ? null : text;
    }
}
