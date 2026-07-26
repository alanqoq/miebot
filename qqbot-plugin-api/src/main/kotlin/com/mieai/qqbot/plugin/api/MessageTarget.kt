package com.mieai.qqbot.plugin.api

/** A QQ target identifier; the target type prevents cross-scope sends. */
data class MessageTarget(
    val type: MessageTargetType,
    val id: String,
) {
    init {
        require(id.isNotBlank() && id == id.trim() && id.codePoints().noneMatch { Character.isWhitespace(it) }) {
            "id must be a non-blank token"
        }
        require(id.length <= 255) { "id is too long" }
    }
}
