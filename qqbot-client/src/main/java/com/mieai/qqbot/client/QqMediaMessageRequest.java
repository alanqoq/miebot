package com.mieai.qqbot.client;

import java.net.URI;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Objects;
import java.util.Optional;

public record QqMediaMessageRequest(
        QqMessageTargetType targetType,
        String targetId,
        QqMediaKind mediaKind,
        URI mediaUrl,
        Optional<String> content,
        Optional<String> replyMessageId,
        Optional<String> replyEventId,
        int messageSequence) {
    public QqMediaMessageRequest {
        Objects.requireNonNull(targetType, "targetType must not be null");
        validateTarget(targetId);
        Objects.requireNonNull(mediaKind, "mediaKind must not be null");
        Objects.requireNonNull(mediaUrl, "mediaUrl must not be null");
        if (!"https".equalsIgnoreCase(mediaUrl.getScheme()) || mediaUrl.getHost() == null
                || mediaUrl.getUserInfo() != null || mediaUrl.getFragment() != null
                || mediaUrl.toString().length() > 2048) {
            throw new IllegalArgumentException("mediaUrl is invalid");
        }
        String host = mediaUrl.getHost();
        if (host.equalsIgnoreCase("localhost") || host.endsWith(".localhost")
                || host.equals("0.0.0.0") || host.equals("::1")
                || isIpLiteral(host) && isPrivateLiteral(host)) {
            throw new IllegalArgumentException("mediaUrl host is not public");
        }
        Objects.requireNonNull(content, "content must not be null");
        content.ifPresent(value -> {
            if (value.codePointCount(0, value.length()) > 4000) throw new IllegalArgumentException("content is too long");
        });
        Objects.requireNonNull(replyMessageId, "replyMessageId must not be null");
        Objects.requireNonNull(replyEventId, "replyEventId must not be null");
        if (messageSequence < 1) throw new IllegalArgumentException("messageSequence must be positive");
    }

    private static void validateTarget(String value) {
        if (value == null || value.isBlank() || !value.equals(value.strip())
                || value.codePoints().anyMatch(Character::isWhitespace)
                || value.indexOf('/') >= 0 || value.indexOf('\\') >= 0
                || value.indexOf('?') >= 0 || value.indexOf('#') >= 0) {
            throw new IllegalArgumentException("targetId is invalid");
        }
    }

    private static boolean isIpLiteral(String host) {
        return host.indexOf(':') >= 0 || host.matches("[0-9.]+")
                || host.matches("[0-9a-fA-F:]+%?[0-9a-zA-Z]*");
    }

    private static boolean isPrivateLiteral(String host) {
        try {
            InetAddress address = InetAddress.getByName(host);
            return address.isAnyLocalAddress() || address.isLoopbackAddress()
                    || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                    || address.isMulticastAddress();
        } catch (UnknownHostException exception) {
            return true;
        }
    }
}
