package com.mieai.qqbot.plugin.api;

import java.util.Objects;
import java.util.Optional;

/** Stable message fields extracted from the QQ Gateway payload. */
public record InboundMessage(
        MessageTarget replyTarget,
        Optional<String> messageId,
        Optional<String> eventId,
        Optional<String> authorId,
        Optional<String> content) {
    public InboundMessage {
        Objects.requireNonNull(replyTarget, "replyTarget must not be null");
        Objects.requireNonNull(messageId, "messageId must not be null");
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(authorId, "authorId must not be null");
        Objects.requireNonNull(content, "content must not be null");
    }
}
