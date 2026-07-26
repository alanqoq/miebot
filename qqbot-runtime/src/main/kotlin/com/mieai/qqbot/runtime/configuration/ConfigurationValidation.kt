package com.mieai.qqbot.runtime.configuration

internal object ConfigurationValidation {
    fun displayName(value: String): String {
        require(value.isNotBlank()) { "displayName must not be blank" }
        require(value == value.trim()) { "displayName must not have surrounding whitespace" }
        require(value.codePoints().noneMatch(Character::isISOControl)) { "displayName must not contain control characters" }
        return value
    }
}
