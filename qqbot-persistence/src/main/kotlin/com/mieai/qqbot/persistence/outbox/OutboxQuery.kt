package com.mieai.qqbot.persistence.outbox

import com.mieai.qqbot.domain.bot.BotEnvironment
import com.mieai.qqbot.domain.bot.BotId
/** Bounded, cursor-based management query for durable Outbox jobs. */
data class OutboxQuery(
    val limit: Int,
    val cursor: String?,
    val environment: BotEnvironment?,
    val botId: BotId?,
    val status: OutboxStatus?,
    val jobType: String?,
    val search: String?,
) {
    init {
        require(limit in 1..100) { "limit must be between 1 and 100" }
        validateNullableToken(cursor, "cursor", 512)
        validateNullableToken(jobType, "jobType", 128)
        validateNullableSearch(search)
    }

    companion object {
        fun firstPage(limit: Int): OutboxQuery = OutboxQuery(
            limit,
            null,
            null,
            null,
            null,
            null,
            null,
        )

        private fun validateNullableToken(value: String?, name: String, maximumLength: Int) {
            value?.let { token ->
                require(token.isNotBlank() && token == token.trim() &&
                    token.codePoints().noneMatch { Character.isWhitespace(it) } &&
                    token.codePoints().noneMatch { Character.isISOControl(it) }) {
                    "$name must be a non-blank token"
                }
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
