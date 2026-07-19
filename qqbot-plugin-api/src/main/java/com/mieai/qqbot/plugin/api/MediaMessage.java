package com.mieai.qqbot.plugin.api;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Controlled media command. The host never downloads the URL or exposes credentials. */
public record MediaMessage(
        MessageTarget target,
        MediaKind kind,
        URI mediaUrl,
        Optional<String> content,
        Optional<String> replyMessageId,
        Optional<String> replyEventId,
        int messageSequence,
        Optional<String> deduplicationKey,
        Optional<UUID> sourceEventId) {
    public MediaMessage {
        Objects.requireNonNull(target, "target must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(mediaUrl, "mediaUrl must not be null");
        validateUrl(mediaUrl);
        content = requireContent(content);
        Objects.requireNonNull(replyMessageId, "replyMessageId must not be null");
        Objects.requireNonNull(replyEventId, "replyEventId must not be null");
        if (messageSequence < 1) throw new IllegalArgumentException("messageSequence must be positive");
        Objects.requireNonNull(deduplicationKey, "deduplicationKey must not be null");
        Objects.requireNonNull(sourceEventId, "sourceEventId must not be null");
        deduplicationKey.ifPresent(value -> {
            if (value.isBlank() || value.length() > 512 || value.codePoints().anyMatch(Character::isWhitespace)) {
                throw new IllegalArgumentException("deduplicationKey is invalid");
            }
        });
    }

    private static Optional<String> requireContent(Optional<String> value) {
        Objects.requireNonNull(value, "content must not be null");
        return value.map(content -> {
            if (content.codePoints().anyMatch(codePoint -> Character.isISOControl(codePoint)
                    && codePoint != '\n' && codePoint != '\r' && codePoint != '\t')
                    || content.codePointCount(0, content.length()) > 4000) {
                throw new IllegalArgumentException("content is invalid");
            }
            return content;
        });
    }

    private static void validateUrl(URI value) {
        if (!"https".equalsIgnoreCase(value.getScheme()) || value.getHost() == null
                || value.getUserInfo() != null || value.getFragment() != null
                || value.toString().length() > 2048) {
            throw new IllegalArgumentException("mediaUrl must be an HTTPS URL without credentials or fragments");
        }
        String host = value.getHost();
        if (host.equalsIgnoreCase("localhost") || host.endsWith(".localhost")
                || host.equals("0.0.0.0") || host.equals("::1")) {
            throw new IllegalArgumentException("mediaUrl host is not allowed");
        }
        try {
            if (isIpLiteral(host)) {
                InetAddress address = InetAddress.getByName(host);
                if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                        || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                        || address.isMulticastAddress()) {
                    throw new IllegalArgumentException("mediaUrl host is not public");
                }
            }
        } catch (UnknownHostException exception) {
            throw new IllegalArgumentException("mediaUrl host is invalid", exception);
        }
    }

    private static boolean isIpLiteral(String host) {
        return host.indexOf(':') >= 0 || host.matches("[0-9.]+") || host.matches("[0-9a-fA-F:]+%?[0-9a-zA-Z]*");
    }
}
