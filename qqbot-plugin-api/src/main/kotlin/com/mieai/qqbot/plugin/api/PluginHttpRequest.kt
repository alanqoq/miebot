package com.mieai.qqbot.plugin.api

import java.net.URI
import java.time.Duration

/** Outbound HTTP request forwarded through the host's JDK HTTP client. */
class PluginHttpRequest(
    val method: String,
    val uri: URI,
    headers: Map<String, String> = emptyMap(),
    body: ByteArray? = null,
    val timeout: Duration = Duration.ofSeconds(10),
) {
    val headers: Map<String, String> = headers.toMap()
    private val bodyBytes: ByteArray? = body?.clone()
    val body: ByteArray?
        get() = bodyBytes?.clone()

    init {
        require(method.isNotBlank()) { "method must not be blank" }
        require(!timeout.isZero && !timeout.isNegative) { "timeout must be positive" }
    }

    companion object {
        fun get(uri: URI): PluginHttpRequest = PluginHttpRequest("GET", uri)
    }
}
