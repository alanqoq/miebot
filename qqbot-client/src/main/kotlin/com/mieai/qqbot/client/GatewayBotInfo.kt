package com.mieai.qqbot.client

import java.net.URI
import java.util.Locale

/** Gateway URL and shard/session recommendations returned by QQ. */
data class GatewayBotInfo(
    val url: URI,
    val recommendedShardCount: Int,
    val sessionStartLimit: SessionStartLimit,
) {
    init {
        val scheme = url.scheme
        require(url.isAbsolute && url.host != null && scheme != null &&
            scheme.lowercase(Locale.ROOT) == "wss") {
            "url must be an absolute WSS URI with a host"
        }
        require(recommendedShardCount > 0) { "recommendedShardCount must be positive" }
    }
}
