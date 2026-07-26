package com.mieai.qqbot.gateway

import com.mieai.qqbot.domain.bot.GatewayIntents
import com.mieai.qqbot.domain.bot.ShardSpec
import java.net.URI
import java.time.Duration
import java.util.Locale

/** Immutable connection and authentication settings for one Gateway session. */
data class GatewaySessionConfig private constructor(
    val gatewayUrl: URI,
    val intents: GatewayIntents,
    val shardSpec: ShardSpec,
    val heartbeatAckTimeout: Duration,
    val identifyProperties: Map<String, String>,
    val helloTimeout: Duration,
    val readyTimeout: Duration,
    private val normalized: Boolean,
) {
    constructor(
        gatewayUrl: URI,
        intents: GatewayIntents,
        shardSpec: ShardSpec,
        heartbeatAckTimeout: Duration,
        identifyProperties: Map<String, String>,
        helloTimeout: Duration = DEFAULT_HELLO_TIMEOUT,
        readyTimeout: Duration = DEFAULT_READY_TIMEOUT,
    ) : this(
        validateGatewayUrl(gatewayUrl),
        intents,
        shardSpec,
        requirePositive(heartbeatAckTimeout, "heartbeatAckTimeout"),
        copyProperties(identifyProperties),
        requirePositive(helloTimeout, "helloTimeout"),
        requirePositive(readyTimeout, "readyTimeout"),
        true,
    )

    companion object {
        private val DEFAULT_ACK_TIMEOUT = Duration.ofSeconds(10)
        private val DEFAULT_HELLO_TIMEOUT = Duration.ofSeconds(10)
        private val DEFAULT_READY_TIMEOUT = Duration.ofSeconds(15)
        private val DEFAULT_PROPERTIES: Map<String, String> = mapOf(
            "\$os" to "linux",
            "\$browser" to "mieai-qqbot",
            "\$device" to "mieai-qqbot",
        )

        fun defaults(
            gatewayUrl: URI,
            intents: GatewayIntents,
            shardSpec: ShardSpec,
        ): GatewaySessionConfig = GatewaySessionConfig(
            gatewayUrl,
            intents,
            shardSpec,
            DEFAULT_ACK_TIMEOUT,
            DEFAULT_PROPERTIES,
            DEFAULT_HELLO_TIMEOUT,
            DEFAULT_READY_TIMEOUT,
        )

        private fun requirePositive(value: Duration, name: String): Duration {
            require(!value.isZero && !value.isNegative) { "$name must be positive" }
            return value
        }

        private fun copyProperties(value: Map<String, String>): Map<String, String> = value.toMap()

        private fun validateGatewayUrl(value: URI): URI {
            val scheme = value.scheme
            require(
                value.isAbsolute &&
                    value.host != null &&
                    scheme != null &&
                    scheme.lowercase(Locale.ROOT) == "wss",
            ) { "gatewayUrl must be an absolute WSS URI with a host" }
            return value
        }
    }
}
