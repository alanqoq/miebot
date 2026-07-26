package com.mieai.qqbot.plugin.api

import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException
import java.util.UUID

/** Controlled media command. The host never downloads the URL or exposes credentials. */
data class MediaMessage(
    val target: MessageTarget,
    val kind: MediaKind,
    val mediaUrl: URI,
    val content: String? = null,
    val replyMessageId: String? = null,
    val replyEventId: String? = null,
    val messageSequence: Int = 1,
    val deduplicationKey: String? = null,
    val sourceEventId: UUID? = null,
) {
    init {
        validateUrl(mediaUrl)
        requireContent(content)
        require(messageSequence >= 1) { "messageSequence must be positive" }
        deduplicationKey?.let { value ->
            require(value.isNotBlank() && value.length <= 512 && value.codePoints().noneMatch { Character.isWhitespace(it) }) {
                "deduplicationKey is invalid"
            }
        }
    }

    companion object {
        private fun requireContent(value: String?) {
            value?.let { text ->
                require(text.codePoints().noneMatch { codePoint ->
                    Character.isISOControl(codePoint) && codePoint != '\n'.code &&
                        codePoint != '\r'.code && codePoint != '\t'.code
                } && text.codePointCount(0, text.length) <= 4000) {
                    "content is invalid"
                }
            }
        }

        private fun validateUrl(value: URI) {
            if (!value.scheme.equals("https", ignoreCase = true) || value.host == null ||
                value.userInfo != null || value.fragment != null || value.toString().length > 2048
            ) {
                throw IllegalArgumentException("mediaUrl must be an HTTPS URL without credentials or fragments")
            }
            val host = value.host ?: throw IllegalArgumentException("mediaUrl host is not allowed")
            if (host.equals("localhost", ignoreCase = true) || host.endsWith(".localhost") ||
                host == "0.0.0.0" || host == "::1"
            ) {
                throw IllegalArgumentException("mediaUrl host is not allowed")
            }
            try {
                if (isIpLiteral(host)) {
                    val address = InetAddress.getByName(host)
                    if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
                        address.isSiteLocalAddress || address.isMulticastAddress
                    ) {
                        throw IllegalArgumentException("mediaUrl host is not public")
                    }
                }
            } catch (exception: UnknownHostException) {
                throw IllegalArgumentException("mediaUrl host is invalid", exception)
            }
        }

        private fun isIpLiteral(host: String): Boolean =
            host.indexOf(':') >= 0 || host.matches(Regex("[0-9.]+")) ||
                host.matches(Regex("[0-9a-fA-F:]+%?[0-9a-zA-Z]*"))
    }
}
