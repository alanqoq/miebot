package com.mieai.qqbot.persistence.bot

/** Opaque, versioned ciphertext envelope and the key identifier needed to decrypt it. */
class SecretCiphertext(
    ciphertext: String,
    keyId: String,
) {
    val ciphertext = requireToken(ciphertext, "ciphertext")
    val keyId = requireToken(keyId, "keyId")

    override fun equals(other: Any?): Boolean =
        this === other || (other is SecretCiphertext && ciphertext == other.ciphertext && keyId == other.keyId)

    override fun hashCode(): Int = 31 * ciphertext.hashCode() + keyId.hashCode()

    override fun toString(): String = "SecretCiphertext[keyId=$keyId, ciphertext=<redacted>]"

    companion object {
        fun of(ciphertext: String, keyId: String): SecretCiphertext = SecretCiphertext(ciphertext, keyId)

        private fun requireToken(value: String, name: String): String {
            require(value.isNotBlank()) { "$name must not be blank" }
            require(value == value.trim()) { "$name must not have surrounding whitespace" }
            require(value.codePoints().noneMatch { Character.isWhitespace(it) }) { "$name must not contain whitespace" }
            require(value.codePoints().noneMatch { Character.isISOControl(it) }) {
                "$name must not contain control characters"
            }
            return value
        }
    }
}
