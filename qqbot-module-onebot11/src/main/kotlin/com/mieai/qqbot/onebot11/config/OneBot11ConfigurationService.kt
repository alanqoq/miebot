package com.mieai.qqbot.onebot11.config

import com.mieai.qqbot.domain.bot.BotId
import com.mieai.qqbot.persistence.bot.BotRepository
import com.mieai.qqbot.runtime.configuration.BotNotFoundException
import com.mieai.qqbot.runtime.security.AesGcmConfigurationSecretCipher
import java.net.URI
import java.time.Clock
import java.util.Arrays

class OneBot11ConfigurationService(
    private val configs: OneBot11ConfigRepository,
    private val bots: BotRepository,
    private val cipher: AesGcmConfigurationSecretCipher,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun get(botId: BotId): OneBot11SettingsView {
        requireBot(botId)
        val now = clock.instant()
        return OneBot11SettingsView.from(
            configs.find(botId) ?: OneBot11Config.defaults(botId, now),
        )
    }

    fun update(botId: BotId, request: OneBot11SettingsRequest): OneBot11SettingsView {
        requireBot(botId)
        val existing = configs.find(botId) ?: OneBot11Config.defaults(botId, clock.instant())
        if (request.expectedRevision != existing.revision) {
            throw OneBot11RevisionConflictException()
        }
        if (request.clearAccessToken && normalizedToken(request.accessToken) != null) {
            throw IllegalArgumentException("accessToken and clearAccessToken cannot be combined")
        }

        val bindAddress = requireBindAddress(request.forwardBindAddress)
        val forwardPort = request.forwardPort
        val reverseUrl = parseReverseUrl(request.reverseUrl)
        validateTransportSettings(request, forwardPort, reverseUrl)

        var encryptedToken = existing.encryptedAccessToken
        if (request.clearAccessToken) {
            encryptedToken = null
        } else {
            normalizedToken(request.accessToken)?.let { replacement ->
                val characters = replacement.toCharArray()
                try {
                    encryptedToken = cipher.encrypt(characters, tokenPurpose(botId))
                } finally {
                    Arrays.fill(characters, '\u0000')
                }
            }
        }
        if (
            request.enabled &&
            (request.forwardEnabled || request.reverseEnabled) &&
            encryptedToken == null
        ) {
            throw IllegalArgumentException(
                "An access token is required when a OneBot transport is enabled",
            )
        }
        if (
            request.enabled &&
            request.forwardEnabled &&
            configs.forwardPortUsedByOther(botId, requireNotNull(forwardPort))
        ) {
            throw IllegalArgumentException("forwardPort is already used by another bot")
        }

        val now = clock.instant()
        val desired = OneBot11Config(
            botId,
            request.enabled,
            request.forwardEnabled,
            bindAddress,
            forwardPort,
            request.reverseEnabled,
            reverseUrl,
            encryptedToken,
            request.heartbeatEnabled,
            request.heartbeatIntervalMs,
            request.reconnectIntervalMs,
            existing.revision,
            existing.createdAt,
            now,
        )
        return OneBot11SettingsView.from(configs.save(desired, existing.revision))
    }

    fun resolve(botId: BotId): ResolvedOneBot11Config? = configs.find(botId)
        ?.takeIf(OneBot11Config::enabled)
        ?.let(::resolve)

    fun resolveEnabled(): List<ResolvedOneBot11Config> = configs.findEnabled().map(::resolve)

    fun enabledBotIds(): List<BotId> = configs.findEnabled().map(OneBot11Config::botId)

    fun isEnabled(botId: BotId): Boolean = configs.find(botId)?.enabled == true

    private fun resolve(config: OneBot11Config): ResolvedOneBot11Config {
        val encrypted = config.encryptedAccessToken
            ?: throw IllegalStateException("Enabled OneBot settings have no access token")
        val decrypted = cipher.decrypt(encrypted, tokenPurpose(config.botId))
        return try {
            ResolvedOneBot11Config(config, String(decrypted))
        } finally {
            Arrays.fill(decrypted, '\u0000')
        }
    }

    private fun requireBot(botId: BotId) {
        if (bots.findById(botId) == null) {
            throw BotNotFoundException(botId)
        }
    }

    companion object {
        private const val MAX_TOKEN_LENGTH = 4_096

        private fun validateTransportSettings(
            request: OneBot11SettingsRequest,
            forwardPort: Int?,
            reverseUrl: URI?,
        ) {
            require(request.expectedRevision >= 0L) { "expectedRevision must not be negative" }
            require(!request.enabled || request.forwardEnabled || request.reverseEnabled) {
                "At least one OneBot transport must be enabled"
            }
            if (
                request.forwardEnabled &&
                (forwardPort == null || forwardPort !in 1..65_535)
            ) {
                throw IllegalArgumentException("forwardPort is required and must be valid")
            }
            require(!request.reverseEnabled || reverseUrl != null) { "reverseUrl is required" }
            require(request.heartbeatIntervalMs in 1_000..300_000) {
                "heartbeatIntervalMs is invalid"
            }
            require(request.reconnectIntervalMs in 500..300_000) {
                "reconnectIntervalMs is invalid"
            }
        }

        private fun requireBindAddress(value: String): String {
            require(
                value.isNotBlank() &&
                    value == value.trim() &&
                    value.length <= 255 &&
                    value.codePoints().noneMatch { Character.isWhitespace(it) } &&
                    value.codePoints().noneMatch { Character.isISOControl(it) },
            ) { "forwardBindAddress is invalid" }
            return value
        }

        private fun parseReverseUrl(value: String?): URI? {
            if (value.isNullOrBlank()) {
                return null
            }
            require(value == value.trim() && value.length <= 2_048) { "reverseUrl is invalid" }
            val uri = try {
                URI.create(value)
            } catch (exception: IllegalArgumentException) {
                throw IllegalArgumentException("reverseUrl is invalid", exception)
            }
            val scheme = uri.scheme
            require(
                uri.isAbsolute &&
                    uri.host != null &&
                    scheme != null &&
                    (scheme.equals("ws", ignoreCase = true) || scheme.equals("wss", ignoreCase = true)) &&
                    uri.userInfo == null &&
                    uri.fragment == null,
            ) { "reverseUrl must be an absolute ws or wss URL" }
            return uri
        }

        private fun normalizedToken(value: String?): String? {
            if (value.isNullOrBlank()) {
                return null
            }
            require(
                value == value.trim() &&
                    value.length <= MAX_TOKEN_LENGTH &&
                    value.codePoints().noneMatch { Character.isWhitespace(it) } &&
                    value.codePoints().noneMatch { Character.isISOControl(it) },
            ) { "accessToken is invalid" }
            return value
        }

        private fun tokenPurpose(botId: BotId): String = "onebot11-access-token:$botId"
    }
}
