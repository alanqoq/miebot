package com.mieai.qqbot.persistence.plugin;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Bounded, cursor-based management query for plugin delivery attempts. */
public record PluginDeliveryQuery(
        int limit,
        Optional<String> cursor,
        Optional<UUID> bindingId,
        Optional<PluginDeliveryStatus> status,
        Optional<String> search) {

    public PluginDeliveryQuery {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
        cursor = requireOptionalText(cursor, "cursor", 512, false);
        bindingId = Objects.requireNonNull(bindingId, "bindingId must not be null");
        status = Objects.requireNonNull(status, "status must not be null");
        search = requireOptionalText(search, "search", 256, true);
    }

    public static PluginDeliveryQuery firstPage(int limit) {
        return new PluginDeliveryQuery(
                limit, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static Optional<String> requireOptionalText(
            Optional<String> value, String name, int maximumLength, boolean allowWhitespace) {
        Objects.requireNonNull(value, name + " must not be null");
        return value.map(candidate -> {
            Objects.requireNonNull(candidate, name + " value must not be null");
            if (candidate.isBlank()
                    || !candidate.equals(candidate.strip())
                    || candidate.codePoints().anyMatch(Character::isISOControl)
                    || (!allowWhitespace && candidate.codePoints().anyMatch(Character::isWhitespace))) {
                throw new IllegalArgumentException(name + " is invalid");
            }
            if (candidate.length() > maximumLength) {
                throw new IllegalArgumentException(name + " is too long");
            }
            return candidate;
        });
    }
}
