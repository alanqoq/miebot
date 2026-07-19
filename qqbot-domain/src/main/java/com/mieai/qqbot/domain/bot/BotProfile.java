package com.mieai.qqbot.domain.bot;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** Stable subset of the QQ profile returned for the authenticated bot. */
public record BotProfile(String platformUserId, String displayName, Optional<URI> avatarUrl) {
    public BotProfile {
        validateIdentifier(platformUserId);
        validateDisplayName(displayName);
        Objects.requireNonNull(avatarUrl, "avatarUrl must not be null");
        avatarUrl.ifPresent(BotProfile::validateAvatarUrl);
    }

    public BotProfile(String platformUserId, String displayName) {
        this(platformUserId, displayName, Optional.empty());
    }

    public BotProfile(String platformUserId, String displayName, URI avatarUrl) {
        this(platformUserId, displayName, Optional.of(Objects.requireNonNull(
                avatarUrl, "avatarUrl must not be null")));
    }

    private static void validateIdentifier(String value) {
        Objects.requireNonNull(value, "platformUserId must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("platformUserId must not be blank");
        }
        if (!value.equals(value.strip())) {
            throw new IllegalArgumentException("platformUserId must not have surrounding whitespace");
        }
        if (value.codePoints().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("platformUserId must not contain whitespace");
        }
        if (value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("platformUserId must not contain control characters");
        }
    }

    private static void validateDisplayName(String value) {
        Objects.requireNonNull(value, "displayName must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("displayName must not be blank");
        }
        if (!value.equals(value.strip())) {
            throw new IllegalArgumentException("displayName must not have surrounding whitespace");
        }
        if (value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("displayName must not contain control characters");
        }
    }

    private static void validateAvatarUrl(URI value) {
        if (!value.isAbsolute() || value.getHost() == null || value.getHost().isBlank()) {
            throw new IllegalArgumentException("avatarUrl must be an absolute URI with a host");
        }
        String scheme = value.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("avatarUrl must use HTTP or HTTPS");
        }
    }
}
