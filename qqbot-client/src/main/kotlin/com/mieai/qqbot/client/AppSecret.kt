package com.mieai.qqbot.client

import java.util.Arrays

/** Secret assigned to a QQ bot application. String rendering is always redacted. */
class AppSecret private constructor(value: CharArray) : AutoCloseable {
    private var value: CharArray? = value.copyOf()

    init {
        validate(value)
    }

    internal fun rawValue(): String = synchronized(this) {
        val current = value ?: throw IllegalStateException("AppSecret has been destroyed")
        String(current)
    }

    @get:Synchronized
    val isDestroyed: Boolean
        get() = value == null

    @Synchronized
    override fun close() {
        value?.let {
            Arrays.fill(it, '\u0000')
            value = null
        }
    }

    override fun toString(): String = "AppSecret[<redacted>]"

    companion object {
        fun of(value: String): AppSecret {
            val characters = value.toCharArray()
            return try {
                AppSecret(characters)
            } finally {
                Arrays.fill(characters, '\u0000')
            }
        }

        fun of(value: CharArray): AppSecret = AppSecret(value)

        private fun validate(value: CharArray) {
            require(value.isNotEmpty()) { "value must not be empty" }
            require(!Character.isWhitespace(value.first()) && !Character.isWhitespace(value.last())) {
                "value must not have surrounding whitespace"
            }
            for (character in value) {
                require(!Character.isWhitespace(character)) { "value must not contain whitespace" }
                require(!Character.isISOControl(character)) { "value must not contain control characters" }
            }
        }
    }
}
