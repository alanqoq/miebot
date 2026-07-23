package com.mieai.qqbot.protocol.event;

import com.mieai.qqbot.protocol.gateway.GatewayEnvelope;
import com.mieai.qqbot.protocol.json.JsonCodec;
import com.mieai.qqbot.protocol.json.JsonCodecs;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Decodes known dispatch types while leaving future event names available as raw payloads. */
public final class QqEventDecoder {
    private static final Map<String, Class<? extends QqEventData>> EVENT_TYPES = Map.ofEntries(
            Map.entry("GUILD_CREATE", QqEventModels.Guild.class),
            Map.entry("GUILD_UPDATE", QqEventModels.Guild.class),
            Map.entry("GUILD_DELETE", QqEventModels.Guild.class),
            Map.entry("CHANNEL_CREATE", QqEventModels.Channel.class),
            Map.entry("CHANNEL_UPDATE", QqEventModels.Channel.class),
            Map.entry("CHANNEL_DELETE", QqEventModels.Channel.class),
            Map.entry("GUILD_MEMBER_ADD", QqEventModels.GuildMember.class),
            Map.entry("GUILD_MEMBER_UPDATE", QqEventModels.GuildMember.class),
            Map.entry("GUILD_MEMBER_REMOVE", QqEventModels.GuildMember.class),
            Map.entry("MESSAGE_CREATE", QqEventModels.Message.class),
            Map.entry("AT_MESSAGE_CREATE", QqEventModels.Message.class),
            Map.entry("DIRECT_MESSAGE_CREATE", QqEventModels.Message.class),
            Map.entry("GROUP_AT_MESSAGE_CREATE", QqEventModels.Message.class),
            Map.entry("GROUP_MESSAGE_CREATE", QqEventModels.Message.class),
            Map.entry("C2C_MESSAGE_CREATE", QqEventModels.Message.class),
            Map.entry("MESSAGE_DELETE", QqEventModels.MessageDelete.class),
            Map.entry("PUBLIC_MESSAGE_DELETE", QqEventModels.MessageDelete.class),
            Map.entry("DIRECT_MESSAGE_DELETE", QqEventModels.MessageDelete.class),
            Map.entry("MESSAGE_REACTION_ADD", QqEventModels.MessageReaction.class),
            Map.entry("MESSAGE_REACTION_REMOVE", QqEventModels.MessageReaction.class),
            Map.entry("MESSAGE_AUDIT_PASS", QqEventModels.MessageAudit.class),
            Map.entry("MESSAGE_AUDIT_REJECT", QqEventModels.MessageAudit.class),
            Map.entry("FORUM_THREAD_CREATE", QqEventModels.ForumThread.class),
            Map.entry("FORUM_THREAD_UPDATE", QqEventModels.ForumThread.class),
            Map.entry("FORUM_THREAD_DELETE", QqEventModels.ForumThread.class),
            Map.entry("FORUM_POST_CREATE", QqEventModels.ForumPost.class),
            Map.entry("FORUM_POST_DELETE", QqEventModels.ForumPost.class),
            Map.entry("FORUM_REPLY_CREATE", QqEventModels.ForumReply.class),
            Map.entry("FORUM_REPLY_DELETE", QqEventModels.ForumReply.class),
            Map.entry("FORUM_PUBLISH_AUDIT_RESULT", QqEventModels.ForumAudit.class),
            Map.entry("AUDIO_START", QqEventModels.AudioAction.class),
            Map.entry("AUDIO_FINISH", QqEventModels.AudioAction.class),
            Map.entry("AUDIO_ON_MIC", QqEventModels.AudioAction.class),
            Map.entry("AUDIO_OFF_MIC", QqEventModels.AudioAction.class),
            Map.entry("AUDIO_OR_LIVE_CHANNEL_MEMBER_ENTER", QqEventModels.AudioLiveMember.class),
            Map.entry("AUDIO_OR_LIVE_CHANNEL_MEMBER_EXIT", QqEventModels.AudioLiveMember.class),
            Map.entry("INTERACTION_CREATE", QqEventModels.Interaction.class),
            Map.entry("FRIEND_ADD", QqEventModels.UserLifecycle.class),
            Map.entry("FRIEND_DEL", QqEventModels.UserLifecycle.class),
            Map.entry("C2C_MSG_RECEIVE", QqEventModels.UserLifecycle.class),
            Map.entry("C2C_MSG_REJECT", QqEventModels.UserLifecycle.class),
            Map.entry("GROUP_ADD_ROBOT", QqEventModels.GroupLifecycle.class),
            Map.entry("GROUP_DEL_ROBOT", QqEventModels.GroupLifecycle.class),
            Map.entry("GROUP_MSG_RECEIVE", QqEventModels.GroupLifecycle.class),
            Map.entry("GROUP_MSG_REJECT", QqEventModels.GroupLifecycle.class),
            Map.entry("GROUP_MEMBER_ADD", QqEventModels.GroupLifecycle.class),
            Map.entry("GROUP_MEMBER_REMOVE", QqEventModels.GroupLifecycle.class),
            Map.entry("SUBSCRIBE_MESSAGE_STATUS", QqEventModels.SubscribeMessageStatus.class),
            Map.entry("ENTER_AIO", QqEventModels.EnterAio.class));

    private static final QqEventDecoder DEFAULT = new QqEventDecoder(JsonCodecs.defaultCodec());

    private final JsonCodec jsonCodec;

    public QqEventDecoder(JsonCodec jsonCodec) {
        this.jsonCodec = Objects.requireNonNull(jsonCodec, "jsonCodec must not be null");
    }

    public static QqEventDecoder defaultDecoder() {
        return DEFAULT;
    }

    public boolean supports(String eventType) {
        return eventType != null && EVENT_TYPES.containsKey(eventType);
    }

    public Optional<QqEventData> decodeKnown(String eventType, String rawGatewayPayload) {
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(rawGatewayPayload, "rawGatewayPayload must not be null");
        Class<? extends QqEventData> type = EVENT_TYPES.get(eventType);
        if (type == null) {
            return Optional.empty();
        }
        GatewayEnvelope<? extends QqEventData> envelope = decodeEnvelope(rawGatewayPayload, type);
        if (envelope.eventType() != null && !eventType.equals(envelope.eventType())) {
            throw new IllegalArgumentException("eventType does not match the Gateway envelope");
        }
        return Optional.ofNullable(envelope.data());
    }

    private <T extends QqEventData> GatewayEnvelope<T> decodeEnvelope(
            String rawGatewayPayload, Class<T> type) {
        return jsonCodec.decodeGatewayEnvelope(rawGatewayPayload, type);
    }
}
