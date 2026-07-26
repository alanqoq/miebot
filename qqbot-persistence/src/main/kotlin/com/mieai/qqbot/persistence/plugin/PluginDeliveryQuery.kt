package com.mieai.qqbot.persistence.plugin

import java.util.UUID

/** Bounded, cursor-based management query for plugin delivery attempts. */
data class PluginDeliveryQuery(
    val limit: Int,
    val cursor: String?,
    val bindingId: UUID?,
    val status: PluginDeliveryStatus?,
    val search: String?,
) {
    init {
        require(limit in 1..100) { "limit must be between 1 and 100" }
        validateNullableText(cursor, "cursor", 512, false)
        validateNullableText(search, "search", 256, true)
    }

    companion object {
        fun firstPage(limit: Int): PluginDeliveryQuery = PluginDeliveryQuery(
            limit,
            null,
            null,
            null,
            null,
        )

        private fun validateNullableText(
            value: String?,
            name: String,
            maximumLength: Int,
            allowWhitespace: Boolean,
        ) {
            value?.let { candidate ->
                require(candidate.isNotBlank() && candidate == candidate.trim() &&
                    candidate.codePoints().noneMatch { Character.isISOControl(it) } &&
                    (allowWhitespace || candidate.codePoints().noneMatch { Character.isWhitespace(it) })) {
                    "$name is invalid"
                }
                require(candidate.length <= maximumLength) { "$name is too long" }
            }
        }
    }
}
