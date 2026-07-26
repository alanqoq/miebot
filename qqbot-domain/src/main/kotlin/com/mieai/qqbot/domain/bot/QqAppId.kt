package com.mieai.qqbot.domain.bot

/** Identifier assigned to a bot application by QQ. */
data class QqAppId(val value: String) {
    init {
        require(value.isNotBlank()) { "value must not be blank" }
        require(value == value.trim()) { "value must not have surrounding whitespace" }
        require(value.codePoints().noneMatch { Character.isWhitespace(it) }) {
            "value must not contain whitespace"
        }
        require(value.codePoints().noneMatch { Character.isISOControl(it) }) {
            "value must not contain control characters"
        }
    }

    override fun toString(): String = value

    companion object {
        fun of(value: String): QqAppId = QqAppId(value)
    }
}
