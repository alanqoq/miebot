package com.mieai.qqbot.runtime.security

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

/** AES-256-GCM encryption for non-bot configuration secrets. */
class AesGcmConfigurationSecretCipher(
    private val keyProvider: KeyProvider,
    private val secureRandom: SecureRandom = SecureRandom(),
) {
    fun encrypt(value: CharArray, purpose: String): EncryptedConfigurationValue {
        validateCharacters(value)
        val requiredPurpose = requirePurpose(purpose)
        val keyMaterial = keyProvider.activeKey()
        val nonce = ByteArray(NONCE_BYTES)
        secureRandom.nextBytes(nonce)
        val plaintext = try {
            encodeUtf8(value)
        } catch (exception: CharacterCodingException) {
            throw SecretEncryptionException(exception)
        }

        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            keyMaterial.initializeCipher(cipher, Cipher.ENCRYPT_MODE, GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(additionalAuthenticatedData(requiredPurpose, keyMaterial.keyId))
            val encrypted = cipher.doFinal(plaintext)
            val envelope = listOf(
                VERSION,
                BASE64_ENCODER.encodeToString(nonce),
                BASE64_ENCODER.encodeToString(encrypted),
            ).joinToString(".")
            return EncryptedConfigurationValue(envelope, keyMaterial.keyId)
        } catch (exception: GeneralSecurityException) {
            throw SecretEncryptionException(exception)
        } finally {
            plaintext.fill(0)
            nonce.fill(0)
        }
    }

    fun decrypt(value: EncryptedConfigurationValue, purpose: String): CharArray {
        val requiredPurpose = requirePurpose(purpose)
        try {
            val envelope = decodeEnvelope(value.ciphertext)
            val keyMaterial = keyProvider.findById(value.keyId)
                ?.takeIf { material -> value.keyId == material.keyId }
                ?: throw KeyUnavailableException()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            keyMaterial.initializeCipher(
                cipher,
                Cipher.DECRYPT_MODE,
                GCMParameterSpec(TAG_BITS, envelope.nonce),
            )
            cipher.updateAAD(additionalAuthenticatedData(requiredPurpose, keyMaterial.keyId))
            val plaintext = cipher.doFinal(envelope.encrypted)
            try {
                return decodeUtf8(plaintext)
            } finally {
                plaintext.fill(0)
            }
        } catch (exception: GeneralSecurityException) {
            throw SecretDecryptionException(exception)
        } catch (exception: IllegalArgumentException) {
            throw SecretDecryptionException(exception)
        } catch (exception: CharacterCodingException) {
            throw SecretDecryptionException(exception)
        }
    }

    private fun decodeEnvelope(value: String): Envelope {
        require(value.length <= MAX_ENVELOPE_CHARACTERS) { "envelope is too long" }
        val parts = value.split('.', limit = 4)
        require(parts.size == 3 && parts[0] == VERSION) { "unsupported envelope" }
        val nonce = BASE64_DECODER.decode(parts[1])
        val encrypted = BASE64_DECODER.decode(parts[2])
        require(nonce.size == NONCE_BYTES && encrypted.size >= TAG_BYTES) {
            "invalid envelope length"
        }
        return Envelope(nonce, encrypted)
    }

    private fun additionalAuthenticatedData(purpose: String, keyId: String): ByteArray =
        listOf("qqbot-configuration-secret", VERSION, keyId, purpose)
            .joinToString("\u0000")
            .toByteArray(StandardCharsets.UTF_8)

    @Throws(CharacterCodingException::class)
    private fun encodeUtf8(characters: CharArray): ByteArray {
        val buffer = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(characters))
        val encoded = ByteArray(buffer.remaining())
        buffer.get(encoded)
        if (buffer.hasArray()) buffer.array().fill(0)
        return encoded
    }

    @Throws(CharacterCodingException::class)
    private fun decodeUtf8(encoded: ByteArray): CharArray {
        val buffer = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(encoded))
        val characters = CharArray(buffer.remaining())
        buffer.get(characters)
        if (buffer.hasArray()) buffer.array().fill('\u0000')
        validateCharacters(characters)
        return characters
    }

    private fun validateCharacters(value: CharArray) {
        require(value.size <= MAX_SECRET_CHARACTERS) { "configuration secret is too long" }
        var index = 0
        while (index < value.size) {
            val character = value[index]
            if (Character.isHighSurrogate(character)) {
                require(index + 1 < value.size && Character.isLowSurrogate(value[index + 1])) {
                    "configuration secret must contain valid Unicode"
                }
                index++
            } else {
                require(!Character.isLowSurrogate(character)) {
                    "configuration secret must contain valid Unicode"
                }
            }
            index++
        }
    }

    private fun requirePurpose(value: String): String {
        require(value.isNotBlank() && value == value.trim()) {
            "purpose must be non-blank without surrounding whitespace"
        }
        return value
    }

    private data class Envelope(val nonce: ByteArray, val encrypted: ByteArray)

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val VERSION = "v1"
        const val NONCE_BYTES = 12
        const val TAG_BITS = 128
        const val TAG_BYTES = TAG_BITS / 8
        const val MAX_ENVELOPE_CHARACTERS = 16_384
        const val MAX_SECRET_CHARACTERS = 4096
        val BASE64_ENCODER: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
        val BASE64_DECODER: Base64.Decoder = Base64.getUrlDecoder()
    }
}
