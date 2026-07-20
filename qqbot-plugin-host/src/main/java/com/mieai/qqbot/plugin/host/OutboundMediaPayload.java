package com.mieai.qqbot.plugin.host;

import com.mieai.qqbot.plugin.api.MediaKind;
import com.mieai.qqbot.plugin.api.MessageTargetType;
import java.util.UUID;

/** Internal durable media payload shared with the production Outbox worker. */
public record OutboundMediaPayload(
        MessageTargetType targetType,
        String targetId,
        MediaKind mediaKind,
        String mediaUrl,
        String content,
        String replyMessageId,
        String replyEventId,
        int messageSequence,
        UUID mediaAssetId) {
    public OutboundMediaPayload(MessageTargetType targetType, String targetId, MediaKind mediaKind,
            String mediaUrl, String content, String replyMessageId, String replyEventId, int messageSequence) {
        this(targetType, targetId, mediaKind, mediaUrl, content, replyMessageId, replyEventId, messageSequence, null);
    }
    public static final String JOB_TYPE = "QQ_SEND_MEDIA";
}
