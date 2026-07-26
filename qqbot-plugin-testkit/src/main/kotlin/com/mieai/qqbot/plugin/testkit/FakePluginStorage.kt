package com.mieai.qqbot.plugin.testkit

import com.mieai.qqbot.plugin.api.PluginStorage
import java.nio.charset.StandardCharsets

/** In-memory binding-local storage with the same public size limits as the production capability. */
class FakePluginStorage : PluginStorage {
    private val values = linkedMapOf<String, MutableMap<String, String>>()

    @Synchronized
    override fun get(namespace: String, key: String): String? {
        validate(namespace, key)
        return values[namespace]?.get(key)
    }

    @Synchronized
    override fun put(namespace: String, key: String, value: String) {
        validate(namespace, key)
        if (value.toByteArray(StandardCharsets.UTF_8).size > PluginStorage.MAX_VALUE_BYTES) {
            throw IllegalArgumentException("value exceeds the storage limit")
        }
        values.computeIfAbsent(namespace) { LinkedHashMap() }[key] = value
    }

    @Synchronized
    override fun delete(namespace: String, key: String) {
        validate(namespace, key)
        values[namespace]?.remove(key)
    }

    @Synchronized
    override fun list(namespace: String): Map<String, String> {
        validateToken(namespace, PluginStorage.MAX_NAMESPACE_LENGTH, "namespace")
        return values[namespace]?.toMap().orEmpty()
    }

    private fun validate(namespace: String, key: String) {
        validateToken(namespace, PluginStorage.MAX_NAMESPACE_LENGTH, "namespace")
        validateToken(key, PluginStorage.MAX_KEY_LENGTH, "key")
    }

    private fun validateToken(value: String?, maximum: Int, name: String) {
        if (value == null || value.isBlank() || value.length > maximum ||
            value.codePoints().anyMatch(Character::isISOControl)
        ) {
            throw IllegalArgumentException("$name is invalid")
        }
    }
}
