package com.mieai.qqbot.runtime.security

import java.security.spec.AlgorithmParameterSpec
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/** Identified AES-256 master key supplied by a [KeyProvider]. */
class KeyMaterial(
    val keyId: String,
    key: SecretKey,
) {
    private val key: SecretKey

    init {
        requireKeyId(keyId)
        require(key.algorithm.equals("AES", ignoreCase = true)) { "key algorithm must be AES" }
        val encoded = key.encoded ?: throw IllegalArgumentException("key must expose RAW key material")
        try {
            require(encoded.size == AES_256_BYTES) { "key must contain exactly 256 bits" }
            this.key = SecretKeySpec(encoded, "AES")
        } finally {
            encoded.fill(0)
        }
    }

    internal fun initializeCipher(
        cipher: Cipher,
        operationMode: Int,
        parameters: AlgorithmParameterSpec,
    ) {
        cipher.init(operationMode, key, parameters)
    }

    override fun toString(): String = "KeyMaterial[keyId=$keyId, key=<redacted>]"

    private companion object {
        const val AES_256_BYTES = 32

        fun requireKeyId(value: String): String {
            require(value.isNotBlank()) { "keyId must not be blank" }
            require(value == value.trim()) { "keyId must not have surrounding whitespace" }
            require(value.codePoints().noneMatch(Character::isWhitespace)) {
                "keyId must not contain whitespace"
            }
            require(value.codePoints().noneMatch(Character::isISOControl)) {
                "keyId must not contain control characters"
            }
            return value
        }
    }
}
