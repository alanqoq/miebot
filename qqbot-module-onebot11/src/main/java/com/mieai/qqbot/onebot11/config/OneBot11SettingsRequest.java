package com.mieai.qqbot.onebot11.config;

/** Complete replacement of one bot's OneBot transport settings. */
public record OneBot11SettingsRequest(
        long expectedRevision,
        boolean enabled,
        boolean forwardEnabled,
        String forwardBindAddress,
        Integer forwardPort,
        boolean reverseEnabled,
        String reverseUrl,
        String accessToken,
        boolean clearAccessToken,
        boolean heartbeatEnabled,
        int heartbeatIntervalMs,
        int reconnectIntervalMs) {}
