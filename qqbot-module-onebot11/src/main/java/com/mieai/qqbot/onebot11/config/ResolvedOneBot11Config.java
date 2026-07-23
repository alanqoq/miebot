package com.mieai.qqbot.onebot11.config;

import java.util.Objects;

/** Runtime settings with the access token decrypted only for an enabled transport. */
public record ResolvedOneBot11Config(OneBot11Config config, String accessToken) {
    public ResolvedOneBot11Config {
        Objects.requireNonNull(config, "config must not be null");
        Objects.requireNonNull(accessToken, "accessToken must not be null");
        if (accessToken.isBlank()) {
            throw new IllegalArgumentException("accessToken must not be blank");
        }
    }
}
