package com.mieai.qqbot.onebot11.config;

import com.mieai.qqbot.domain.bot.BotId;
import com.mieai.qqbot.runtime.security.EncryptedConfigurationValue;
import java.net.URI;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

public record OneBot11Config(
        BotId botId,
        boolean enabled,
        boolean forwardEnabled,
        String forwardBindAddress,
        OptionalInt forwardPort,
        boolean reverseEnabled,
        Optional<URI> reverseUrl,
        Optional<EncryptedConfigurationValue> encryptedAccessToken,
        boolean heartbeatEnabled,
        int heartbeatIntervalMs,
        int reconnectIntervalMs,
        long revision,
        Instant createdAt,
        Instant updatedAt) {

    public OneBot11Config {
        Objects.requireNonNull(botId, "botId must not be null");
        forwardBindAddress = requireText(forwardBindAddress, "forwardBindAddress", 255);
        Objects.requireNonNull(forwardPort, "forwardPort must not be null");
        forwardPort.ifPresent(value -> {
            if (value < 1 || value > 65_535) {
                throw new IllegalArgumentException("forwardPort is invalid");
            }
        });
        reverseUrl = Objects.requireNonNull(reverseUrl, "reverseUrl must not be null");
        encryptedAccessToken = Objects.requireNonNull(
                encryptedAccessToken, "encryptedAccessToken must not be null");
        if (heartbeatIntervalMs < 1_000 || heartbeatIntervalMs > 300_000) {
            throw new IllegalArgumentException("heartbeatIntervalMs is invalid");
        }
        if (reconnectIntervalMs < 500 || reconnectIntervalMs > 300_000) {
            throw new IllegalArgumentException("reconnectIntervalMs is invalid");
        }
        if (revision < 0) {
            throw new IllegalArgumentException("revision must not be negative");
        }
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }

    public static OneBot11Config defaults(BotId botId, Instant now) {
        return new OneBot11Config(botId, false, false, "127.0.0.1", OptionalInt.empty(),
                false, Optional.empty(), Optional.empty(), true, 15_000, 3_000,
                0L, now, now);
    }

    private static String requireText(String value, String name, int maxLength) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank() || !value.equals(value.strip())
                || value.length() > maxLength
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return value;
    }
}
