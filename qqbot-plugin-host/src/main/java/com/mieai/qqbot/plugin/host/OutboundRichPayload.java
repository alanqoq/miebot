package com.mieai.qqbot.plugin.host;

import com.mieai.qqbot.plugin.api.MessageTargetType;
import com.mieai.qqbot.plugin.api.RichMessageKind;
import java.util.Map;

public record OutboundRichPayload(
        MessageTargetType targetType,
        String targetId,
        RichMessageKind kind,
        Map<String, Object> payload,
        String replyMessageId,
        String replyEventId,
        int messageSequence) {
    public static final String JOB_TYPE = "QQ_SEND_RICH";
}
