package com.mieai.qqbot.plugin.api

/** Binding-scoped key/value storage; implementations must never expose arbitrary SQL. */
interface PluginStorage {
    fun get(namespace: String, key: String): String?

    fun put(namespace: String, key: String, value: String)

    fun delete(namespace: String, key: String)

    fun list(namespace: String): Map<String, String>

    companion object {
        val MAX_NAMESPACE_LENGTH = 64

        val MAX_KEY_LENGTH = 128

        val MAX_VALUE_BYTES = 64 * 1024

        fun denied(): PluginStorage = object : PluginStorage {
            override fun get(namespace: String, key: String): String? {
                throw SecurityException("Plugin storage capability is not granted")
            }

            override fun put(namespace: String, key: String, value: String) {
                throw SecurityException("Plugin storage capability is not granted")
            }

            override fun delete(namespace: String, key: String) {
                throw SecurityException("Plugin storage capability is not granted")
            }

            override fun list(namespace: String): Map<String, String> {
                throw SecurityException("Plugin storage capability is not granted")
            }
        }
    }
}
