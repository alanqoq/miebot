package com.mieai.qqbot.onebot11.config

data class OneBot11SettingsView(
    val botId: String,
    val enabled: Boolean,
    val forwardEnabled: Boolean,
    val forwardBindAddress: String,
    val forwardPort: Int?,
    val reverseEnabled: Boolean,
    val reverseUrl: String?,
    val accessTokenConfigured: Boolean,
    val heartbeatEnabled: Boolean,
    val heartbeatIntervalMs: Int,
    val reconnectIntervalMs: Int,
    val revision: Long,
) {
    companion object {
        fun from(value: OneBot11Config): OneBot11SettingsView = OneBot11SettingsView(
            value.botId.toString(),
            value.enabled,
            value.forwardEnabled,
            value.forwardBindAddress,
            value.forwardPort,
            value.reverseEnabled,
            value.reverseUrl?.toString(),
            value.encryptedAccessToken != null,
            value.heartbeatEnabled,
            value.heartbeatIntervalMs,
            value.reconnectIntervalMs,
            value.revision,
        )
    }
}
