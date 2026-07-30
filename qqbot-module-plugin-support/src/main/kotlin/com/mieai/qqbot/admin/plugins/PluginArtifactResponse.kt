package com.mieai.qqbot.admin.plugins

import java.time.Instant

/** Read-only metadata for a plugin artifact discovered in the mounted directory. */
data class PluginArtifactResponse(
    val id: String,
    val name: String,
    val version: String,
    val apiCompatibility: String,
    val fileName: String,
    val sizeBytes: Long,
    val modifiedAt: Instant,
    val sha256: String,
    val status: String,
    val error: String?,
    val defaultConfigJson: String?,
    val loaded: Boolean,
    val bindingCount: Int,
    val enabledBindingCount: Int,
    val defaultConfigContent: String? = defaultConfigJson,
    val configFormat: String? = if (defaultConfigJson == null) null else "JSON",
    val configFileName: String? = if (defaultConfigJson == null) null else "config.json",
)
