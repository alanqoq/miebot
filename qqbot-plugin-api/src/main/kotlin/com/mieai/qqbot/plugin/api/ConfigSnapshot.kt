package com.mieai.qqbot.plugin.api

import java.time.Instant

/** Immutable binding configuration captured when a plugin instance is created. */
data class ConfigSnapshot(
    val json: String,
    val revision: Long,
    val loadedAt: Instant,
) {
    init {
        require(json.isNotBlank()) { "json must not be blank" }
        require(revision >= 0L) { "revision must not be negative" }
    }
}
