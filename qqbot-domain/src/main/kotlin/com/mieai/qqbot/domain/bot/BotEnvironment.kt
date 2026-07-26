package com.mieai.qqbot.domain.bot

import java.util.Locale

/** QQ environment whose sessions and target identifiers must remain isolated. */
enum class BotEnvironment {
    SANDBOX,
    PRODUCTION,
    ;

    fun isSandbox(): Boolean = this == SANDBOX

    companion object {
        fun parse(value: String): BotEnvironment {
            require(value.isNotBlank()) { "value must not be blank" }

            return try {
                valueOf(value.trim().uppercase(Locale.ROOT))
            } catch (exception: IllegalArgumentException) {
                throw IllegalArgumentException("unsupported bot environment: $value", exception)
            }
        }
    }
}
