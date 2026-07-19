package com.mieai.qqbot.plugin.api;

import static java.util.Objects.requireNonNull;

/** A QQ target identifier; the target type prevents cross-scope sends. */
public record MessageTarget(MessageTargetType type, String id) {
    public MessageTarget {
        requireNonNull(type, "type must not be null");
        requireNonNull(id, "id must not be null");
        if (id.isBlank() || !id.equals(id.strip()) || id.codePoints().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("id must be a non-blank token");
        }
        if (id.length() > 255) throw new IllegalArgumentException("id is too long");
    }
}
