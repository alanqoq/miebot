package com.mieai.qqbot.persistence.inbox

import com.mieai.qqbot.domain.bot.BotEnvironment
import com.mieai.qqbot.domain.bot.BotId
/** Bounded, cursor-based Inbox query shared by the HTTP adapter and JDBC implementation. */
data class InboxQuery(
    val limit: Int,
    val cursor: String?,
    val environment: BotEnvironment?,
    val botId: BotId?,
    val status: InboxStatus?,
    val eventType: String?,
    val search: String?,
) {
    init {
        require(limit in 1..100) { "limit must be between 1 and 100" }
        validateNullableToken(cursor, "cursor")
        validateNullableToken(eventType, "eventType")
        validateNullableSearch(search)
    }

    companion object {
        fun firstPage(limit: Int): InboxQuery = InboxQuery(
            limit,
            null,
            null,
            null,
            null,
            null,
            null,
        )

        private fun validateNullableToken(value: String?, name: String) {
            value?.let { token ->
                require(token.isNotBlank() && token == token.trim() &&
                    token.codePoints().noneMatch { Character.isWhitespace(it) } &&
                    token.codePoints().noneMatch { Character.isISOControl(it) }) {
                    "$name must be a non-blank token"
                }
                val maximumLength = if (name == "cursor") 512 else 128
                require(token.length <= maximumLength) { "$name is too long" }
            }
        }

        private fun validateNullableSearch(value: String?) {
            value?.let { search ->
                require(search.isNotBlank() && search == search.trim() &&
                    search.codePoints().noneMatch { Character.isISOControl(it) }) {
                    "search must be a non-blank value"
                }
                require(search.length <= 256) { "search is too long" }
            }
        }
    }
}
