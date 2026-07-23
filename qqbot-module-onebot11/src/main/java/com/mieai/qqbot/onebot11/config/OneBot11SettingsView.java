package com.mieai.qqbot.onebot11.config;

public record OneBot11SettingsView(
        String botId,
        boolean enabled,
        boolean forwardEnabled,
        String forwardBindAddress,
        Integer forwardPort,
        boolean reverseEnabled,
        String reverseUrl,
        boolean accessTokenConfigured,
        boolean heartbeatEnabled,
        int heartbeatIntervalMs,
        int reconnectIntervalMs,
        long revision) {

    static OneBot11SettingsView from(OneBot11Config value) {
        return new OneBot11SettingsView(
                value.botId().toString(),
                value.enabled(),
                value.forwardEnabled(),
                value.forwardBindAddress(),
                value.forwardPort().isPresent() ? value.forwardPort().getAsInt() : null,
                value.reverseEnabled(),
                value.reverseUrl().map(Object::toString).orElse(null),
                value.encryptedAccessToken().isPresent(),
                value.heartbeatEnabled(),
                value.heartbeatIntervalMs(),
                value.reconnectIntervalMs(),
                value.revision());
    }
}
