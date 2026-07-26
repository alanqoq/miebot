package com.mieai.qqbot.plugin.api

import java.util.UUID

/** Opaque host-owned media handle; the plugin never receives a filesystem path. */
data class StagedMedia(
    val id: UUID,
    val kind: MediaKind,
    val fileName: String,
    val sizeBytes: Long,
) {
    init {
        require(fileName.isNotBlank()) { "fileName is invalid" }
        require(sizeBytes >= 1L) { "sizeBytes must be positive" }
    }
}
