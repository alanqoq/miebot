package com.mieai.qqbot.plugin.api

import java.nio.charset.StandardCharsets

/** HTTP response returned without exposing the host HTTP client implementation. */
class PluginHttpResponse(
    val statusCode: Int,
    headers: Map<String, List<String>>,
    body: ByteArray,
) {
    val headers: Map<String, List<String>> = headers.mapValues { (_, values) -> values.toList() }
    private val bodyBytes: ByteArray = body.clone()
    val body: ByteArray
        get() = bodyBytes.clone()

    init {
        require(statusCode in 100..599) { "statusCode is invalid" }
    }

    fun bodyAsUtf8(): String = String(bodyBytes, StandardCharsets.UTF_8)
}
