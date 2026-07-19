package com.mieai.qqbot.plugin.host;

import com.mieai.qqbot.plugin.api.MediaKind;
import com.mieai.qqbot.plugin.api.MessageTargetType;

/** Internal durable media payload shared with the production Outbox worker. */
public record OutboundMediaPayload(
        MessageTargetType targetType,
        String targetId,
        MediaKind mediaKind,
        String mediaUrl,
        String content,
        String replyMessageId,
        String replyEventId,
        int messageSequence) {
    public static final String JOB_TYPE = "QQ_SEND_MEDIA";
}
