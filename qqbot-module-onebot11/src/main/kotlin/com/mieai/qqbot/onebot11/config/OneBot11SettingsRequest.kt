package com.mieai.qqbot.onebot11.config

/** Complete replacement of one bot's OneBot transport settings. */
data class OneBot11SettingsRequest(
    val expectedRevision: Long,
    val enabled: Boolean,
    val forwardEnabled: Boolean,
    val forwardBindAddress: String,
    val forwardPort: Int?,
    val reverseEnabled: Boolean,
    val reverseUrl: String?,
    val accessToken: String?,
    val clearAccessToken: Boolean,
    val heartbeatEnabled: Boolean,
    val heartbeatIntervalMs: Int,
    val reconnectIntervalMs: Int,
)
