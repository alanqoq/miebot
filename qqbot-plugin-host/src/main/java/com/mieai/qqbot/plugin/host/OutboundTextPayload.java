package com.mieai.qqbot.plugin.host;

import com.mieai.qqbot.plugin.api.MessageTargetType;

/** Internal durable payload shared with the production Outbox worker. */
public record OutboundTextPayload(
        MessageTargetType targetType,
        String targetId,
        String content,
        String replyMessageId,
        String replyEventId,
        int messageSequence) {
    public static final String JOB_TYPE = "QQ_SEND_TEXT";
}
