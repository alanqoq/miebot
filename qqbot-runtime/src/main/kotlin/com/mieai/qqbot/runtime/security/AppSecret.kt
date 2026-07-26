package com.mieai.qqbot.runtime.security
class AppSecret private constructor(value: CharArray) : AutoCloseable {
    private var value: CharArray? = value.clone()
    init { validate(value) }
    companion object {
        private const val MAX_CHARACTERS = 4096
        fun of(value: String): AppSecret { val chars = value.toCharArray(); return try { AppSecret(chars) } finally { chars.fill('\u0000') } }
        fun of(value: CharArray): AppSecret = AppSecret(value)
        private fun validate(v: CharArray) { require(v.isNotEmpty()) { "value must not be empty" }; require(v.size <= MAX_CHARACTERS) { "value is too long" }; require(!v.first().isWhitespace() && !v.last().isWhitespace()) { "value must not have surrounding whitespace" }; v.forEachIndexed { i,c -> require(!c.isWhitespace()) { "value must not contain whitespace" }; require(!c.isISOControl()) { "value must not contain control characters" }; if (c.isHighSurrogate()) require(i+1<v.size && v[i+1].isLowSurrogate()) { "value must contain valid Unicode" } else require(!c.isLowSurrogate()) { "value must contain valid Unicode" } } }
    }
    @Synchronized
    internal fun copyValue(): CharArray = value?.clone() ?: error("AppSecret has been destroyed")
    @get:Synchronized val isDestroyed: Boolean get() = value == null
    @Synchronized override fun close() { value?.fill('\u0000'); value = null }
    override fun toString() = "AppSecret[<redacted>]"
}
