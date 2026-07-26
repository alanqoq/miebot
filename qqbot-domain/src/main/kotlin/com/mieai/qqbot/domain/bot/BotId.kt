package com.mieai.qqbot.domain.bot

import java.util.UUID

/** Stable internal identifier for a configured bot. */
data class BotId(val value: UUID) {
    init {
        require(value != NIL_UUID) { "value must not be the nil UUID" }
    }

    override fun toString(): String = value.toString()

    companion object {
        private val NIL_UUID = UUID(0L, 0L)

        fun of(value: UUID): BotId = BotId(value)

        fun parse(value: String): BotId {
            require(value.isNotBlank()) { "value must not be blank" }
            require(value == value.trim()) { "value must not have surrounding whitespace" }

            return try {
                val parsed = UUID.fromString(value)
                require(parsed.toString().equals(value, ignoreCase = true)) {
                    "value must use the canonical UUID format"
                }
                BotId(parsed)
            } catch (exception: IllegalArgumentException) {
                throw IllegalArgumentException("value must be a canonical UUID", exception)
            }
        }
    }
}
