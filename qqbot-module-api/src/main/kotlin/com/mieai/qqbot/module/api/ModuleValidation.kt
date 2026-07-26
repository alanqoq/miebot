package com.mieai.qqbot.module.api

internal object ModuleValidation {
    private val ID_PATTERN = Regex("[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*")
    private val VERSION_PATTERN = Regex("(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)(?:[-+][0-9A-Za-z.-]+)?")

    fun requireId(value: String?, name: String): String {
        val text = requireText(value, name, 128)
        require(ID_PATTERN.matches(text)) { "$name is invalid" }
        return text
    }

    fun requireVersion(value: String?, name: String): String {
        val text = requireText(value, name, 64)
        require(VERSION_PATTERN.matches(text)) { "$name is not a semantic version" }
        return text
    }

    fun requireText(value: String?, name: String, maximumLength: Int): String {
        require(!value.isNullOrBlank()) { "$name must not be blank" }
        val text = value.trim()
        require(value == text) { "$name must not have surrounding whitespace" }
        require(text.length <= maximumLength) { "$name is too long" }
        return text
    }
}
