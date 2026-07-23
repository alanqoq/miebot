package com.mieai.qqbot.runtime.outbox;

import com.mieai.qqbot.client.QqMessageTargetType;

/** Durable text-message contract consumed by the production Outbox worker. */
public record OutboundTextPayload(
        QqMessageTargetType targetType,
        String targetId,
        String content,
        String replyMessageId,
        String replyEventId,
        int messageSequence) {
    public static final String JOB_TYPE = "QQ_SEND_TEXT";
}
