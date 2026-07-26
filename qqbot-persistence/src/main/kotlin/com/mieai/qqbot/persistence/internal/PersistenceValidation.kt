package com.mieai.qqbot.persistence.internal

import java.util.UUID

/** Shared validation for persistence input values that become keys or discriminator columns. */
object PersistenceValidation {
    private val nilUuid = UUID(0L, 0L)

    fun requireIdentifier(value: UUID, name: String): UUID {
        require(value != nilUuid) { "$name must not be the nil UUID" }
        return value
    }

    fun requireToken(value: String, name: String): String {
        require(value.isNotBlank()) { "$name must not be blank" }
        require(value == value.trim()) { "$name must not have surrounding whitespace" }
        require(value.codePoints().noneMatch { Character.isWhitespace(it) }) { "$name must not contain whitespace" }
        require(value.codePoints().noneMatch { Character.isISOControl(it) }) {
            "$name must not contain control characters"
        }
        return value
    }

    fun requireToken(value: String, name: String, maxCharacters: Int): String {
        val normalized = requireToken(value, name)
        require(normalized.codePointCount(0, normalized.length) <= maxCharacters) {
            "$name must not exceed $maxCharacters characters"
        }
        return normalized
    }

    fun requirePayload(value: String, name: String): String {
        require(value.isNotBlank()) { "$name must not be blank" }
        return value
    }
}
