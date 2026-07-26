package com.mieai.qqbot.onebot11.config

import com.mieai.qqbot.domain.bot.BotId
import com.mieai.qqbot.runtime.security.EncryptedConfigurationValue
import java.net.URI
import java.time.Instant

data class OneBot11Config(
    val botId: BotId,
    val enabled: Boolean,
    val forwardEnabled: Boolean,
    val forwardBindAddress: String,
    val forwardPort: Int?,
    val reverseEnabled: Boolean,
    val reverseUrl: URI?,
    val encryptedAccessToken: EncryptedConfigurationValue?,
    val heartbeatEnabled: Boolean,
    val heartbeatIntervalMs: Int,
    val reconnectIntervalMs: Int,
    val revision: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    init {
        requireText(forwardBindAddress, "forwardBindAddress", 255)
        if (forwardPort != null) {
            require(forwardPort in 1..65_535) { "forwardPort is invalid" }
        }
        require(heartbeatIntervalMs in 1_000..300_000) { "heartbeatIntervalMs is invalid" }
        require(reconnectIntervalMs in 500..300_000) { "reconnectIntervalMs is invalid" }
        require(revision >= 0) { "revision must not be negative" }
    }

    companion object {
        fun defaults(botId: BotId, now: Instant): OneBot11Config = OneBot11Config(
            botId,
            false,
            false,
            "127.0.0.1",
            null,
            false,
            null,
            null,
            true,
            15_000,
            3_000,
            0L,
            now,
            now,
        )

        private fun requireText(value: String, name: String, maxLength: Int): String {
            require(
                value.isNotBlank() &&
                    value == value.trim() &&
                    value.length <= maxLength &&
                    value.codePoints().noneMatch { Character.isISOControl(it) },
            ) { "$name is invalid" }
            return value
        }
    }
}
