package com.mieai.qqbot.persistence.plugin

import com.mieai.qqbot.persistence.internal.PersistenceValidation
import java.time.Instant

/** Durable metadata for one operator-installed plugin artifact. */
data class PluginArtifact(
    val pluginId: String,
    val name: String,
    val version: String,
    val apiCompatibility: String,
    val fileName: String,
    val sha256: String,
    val entrypoint: String,
    val status: String,
    val enabled: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    init {
        PersistenceValidation.requireToken(pluginId, "pluginId", 128)
        requireText(name, "name", 255)
        PersistenceValidation.requireToken(version, "version", 128)
        requireText(apiCompatibility, "apiCompatibility", 128)
        requireText(fileName, "fileName", 255)
        PersistenceValidation.requireToken(sha256, "sha256", 128)
        PersistenceValidation.requireToken(entrypoint, "entrypoint", 255)
        PersistenceValidation.requireToken(status, "status", 32)
        require(!updatedAt.isBefore(createdAt)) { "updatedAt must not be before createdAt" }
    }

    private fun requireText(value: String, name: String, maximum: Int) {
        require(value.isNotBlank() && value == value.trim() &&
            value.codePoints().noneMatch { Character.isISOControl(it) } &&
            value.codePointCount(0, value.length) <= maximum) {
            "$name is invalid"
        }
    }
}
