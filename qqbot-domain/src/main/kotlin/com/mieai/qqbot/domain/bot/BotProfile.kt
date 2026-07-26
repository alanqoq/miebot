package com.mieai.qqbot.domain.bot

import java.net.URI
import java.util.Locale

/** Stable subset of the QQ profile returned for the authenticated bot. */
data class BotProfile(
    val platformUserId: String,
    val displayName: String,
    val avatarUrl: URI? = null,
) {
    init {
        validateIdentifier(platformUserId)
        validateDisplayName(displayName)
        avatarUrl?.let(::validateAvatarUrl)
    }

    private fun validateIdentifier(value: String) {
        require(value.isNotBlank()) { "platformUserId must not be blank" }
        require(value == value.trim()) { "platformUserId must not have surrounding whitespace" }
        require(value.codePoints().noneMatch { Character.isWhitespace(it) }) {
            "platformUserId must not contain whitespace"
        }
        require(value.codePoints().noneMatch { Character.isISOControl(it) }) {
            "platformUserId must not contain control characters"
        }
    }

    private fun validateDisplayName(value: String) {
        require(value.isNotBlank()) { "displayName must not be blank" }
        require(value == value.trim()) { "displayName must not have surrounding whitespace" }
        require(value.codePoints().noneMatch { Character.isISOControl(it) }) {
            "displayName must not contain control characters"
        }
    }

    private fun validateAvatarUrl(value: URI) {
        require(value.isAbsolute && !value.host.isNullOrBlank()) {
            "avatarUrl must be an absolute URI with a host"
        }
        val scheme = value.scheme.lowercase(Locale.ROOT)
        require(scheme == "http" || scheme == "https") { "avatarUrl must use HTTP or HTTPS" }
    }
}
