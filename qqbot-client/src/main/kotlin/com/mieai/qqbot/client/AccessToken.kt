package com.mieai.qqbot.client

import java.time.Duration
import java.time.Instant

/** Short-lived QQ OpenAPI credential. String rendering is always redacted. */
class AccessToken private constructor(
    private val value: String,
    val expiresAt: Instant,
) {
    fun isUsableAt(instant: Instant, refreshSkew: Duration): Boolean {
        require(!refreshSkew.isNegative) { "refreshSkew must not be negative" }
        return Duration.between(instant, expiresAt) > refreshSkew
    }

    /**
     * Returns the complete QQ authorization value for trusted transport implementations.
     * The returned value is sensitive and must never be logged or exposed to plugins.
     */
    fun authorizationHeaderValue(): String = "QQBot $value"

    override fun toString(): String = "AccessToken[value=<redacted>, expiresAt=$expiresAt]"

    companion object {
        fun of(value: String, expiresAt: Instant): AccessToken = AccessToken(validateValue(value), expiresAt)

        private fun validateValue(value: String): String {
            require(value.isNotBlank()) { "value must not be blank" }
            require(value == value.trim()) { "value must not have surrounding whitespace" }
            require(value.codePoints().noneMatch(Character::isWhitespace)) {
                "value must not contain whitespace"
            }
            require(value.codePoints().noneMatch(Character::isISOControl)) {
                "value must not contain control characters"
            }
            return value
        }
    }
}
