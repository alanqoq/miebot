package com.mieai.qqbot.runtime.outbox;

import com.mieai.qqbot.client.QqMessageTargetType;
import com.mieai.qqbot.client.QqRichMessageKind;
import java.util.Map;

/** Durable rich-message contract consumed by the production Outbox worker. */
public record OutboundRichPayload(
        QqMessageTargetType targetType,
        String targetId,
        QqRichMessageKind kind,
        Map<String, Object> payload,
        String replyMessageId,
        String replyEventId,
        int messageSequence) {
    public static final String JOB_TYPE = "QQ_SEND_RICH";
}
