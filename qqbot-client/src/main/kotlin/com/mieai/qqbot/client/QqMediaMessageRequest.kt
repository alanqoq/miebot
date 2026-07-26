package com.mieai.qqbot.client

import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException

data class QqMediaMessageRequest(
    val targetType: QqMessageTargetType,
    val targetId: String,
    val mediaKind: QqMediaKind,
    val mediaUrl: URI,
    val content: String?,
    val replyMessageId: String?,
    val replyEventId: String?,
    val messageSequence: Int,
) {
    init {
        validateTarget(targetId)
        require(mediaUrl.scheme.equals("https", ignoreCase = true) && mediaUrl.host != null &&
            mediaUrl.userInfo == null && mediaUrl.fragment == null && mediaUrl.toString().length <= 2048) {
            "mediaUrl is invalid"
        }
        val host = mediaUrl.host
        require(!host.equals("localhost", ignoreCase = true) && !host.endsWith(".localhost") &&
            host != "0.0.0.0" && host != "::1" && !(isIpLiteral(host) && isPrivateLiteral(host))) {
            "mediaUrl host is not public"
        }
        content?.let {
            require(it.codePointCount(0, it.length) <= 4000) { "content is too long" }
        }
        require(messageSequence >= 1) { "messageSequence must be positive" }
    }

    companion object {
        private fun validateTarget(value: String) {
            require(!value.isBlank() && value == value.trim() &&
                value.codePoints().noneMatch(Character::isWhitespace) &&
                '/' !in value && '\\' !in value && '?' !in value && '#' !in value) {
                "targetId is invalid"
            }
        }

        private fun isIpLiteral(host: String): Boolean =
            ':' in host || host.matches(Regex("[0-9.]+")) ||
                host.matches(Regex("[0-9a-fA-F:]+%?[0-9a-zA-Z]*"))

        private fun isPrivateLiteral(host: String): Boolean = try {
            val address = InetAddress.getByName(host)
            address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
                address.isSiteLocalAddress || address.isMulticastAddress
        } catch (_: UnknownHostException) {
            true
        }
    }
}
