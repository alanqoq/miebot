package com.mieai.qqbot.persistence.plugin

import java.time.Instant
import java.util.UUID

interface PluginStorageRepository {
    fun find(bindingId: UUID, namespace: String, key: String): String?

    fun put(bindingId: UUID, namespace: String, key: String, value: String, updatedAt: Instant)

    fun delete(bindingId: UUID, namespace: String, key: String)

    fun list(bindingId: UUID, namespace: String): Map<String, String>
}
