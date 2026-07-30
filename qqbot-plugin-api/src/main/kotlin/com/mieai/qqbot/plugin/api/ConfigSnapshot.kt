package com.mieai.qqbot.plugin.api

import java.time.Instant

/** Immutable binding configuration captured when a plugin instance is created. */
data class ConfigSnapshot @JvmOverloads constructor(
    @Deprecated("Use content; this accessor is retained for plugin API 3.1 binary compatibility")
    val json: String,
    val revision: Long,
    val loadedAt: Instant,
    val fileName: String = DEFAULT_FILE_NAME,
) {
    /** Configuration text exactly as stored in the binding file. */
    @Suppress("DEPRECATION")
    val content: String
        get() = json

    init {
        @Suppress("DEPRECATION")
        require(json.isNotBlank()) { "json must not be blank" }
        require(revision >= 0L) { "revision must not be negative" }
        require(
            fileName.isNotBlank() &&
                fileName == fileName.trim() &&
                !fileName.contains('/') &&
                !fileName.contains('\\') &&
                fileName.codePoints().noneMatch(Character::isISOControl)
        ) { "fileName must be a plain file name" }
    }

    @Suppress("DEPRECATION")
    @Deprecated("Retained for plugin API 3.1 binary compatibility", level = DeprecationLevel.HIDDEN)
    fun copy(
        json: String,
        revision: Long,
        loadedAt: Instant,
    ): ConfigSnapshot = ConfigSnapshot(json, revision, loadedAt, fileName)

    companion object {
        const val DEFAULT_FILE_NAME = "config.json"

        @JvmStatic
        @Suppress("UNUSED_PARAMETER", "DEPRECATION")
        @Deprecated("Retained for plugin API 3.1 binary compatibility", level = DeprecationLevel.HIDDEN)
        fun `copy$default`(
            source: ConfigSnapshot,
            json: String?,
            revision: Long,
            loadedAt: Instant?,
            mask: Int,
            marker: Any?,
        ): ConfigSnapshot = ConfigSnapshot(
            json = if (mask and 0x01 != 0) source.json else requireNotNull(json),
            revision = if (mask and 0x02 != 0) source.revision else revision,
            loadedAt = if (mask and 0x04 != 0) source.loadedAt else requireNotNull(loadedAt),
            fileName = source.fileName,
        )
    }
}
