package com.mieai.qqbot.runtime.security

import javax.crypto.spec.SecretKeySpec

class StaticKeyProvider private constructor(private val keyMaterial: KeyMaterial?) : KeyProvider {
    val isConfigured: Boolean
        get() = keyMaterial != null

    override fun activeKey(): KeyMaterial = keyMaterial ?: throw KeyUnavailableException()

    override fun findById(keyId: String): KeyMaterial? {
        val configured = keyMaterial ?: throw KeyUnavailableException()
        return configured.takeIf { it.keyId == keyId }
    }

    override fun toString(): String =
        "StaticKeyProvider[configured=${keyMaterial != null}, key=<redacted>]"

    companion object {
        fun configured(keyId: String, keyBytes: ByteArray) =
            StaticKeyProvider(KeyMaterial(keyId, SecretKeySpec(keyBytes, "AES")))

        fun unconfigured() = StaticKeyProvider(null)
    }
}
