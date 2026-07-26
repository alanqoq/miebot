package com.mieai.qqbot.runtime.security

import com.mieai.qqbot.persistence.bot.SecretCiphertext
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

/** AES-256-GCM AppSecret encryption using a strict version-one envelope. */
class AesGcmAppSecretCipher(
    private val keyProvider: KeyProvider,
    private val secureRandom: SecureRandom = SecureRandom(),
) : AppSecretCipher {
    override fun encrypt(secret: AppSecret, binding: AppSecretBinding): SecretCiphertext {
        val keyMaterial = keyProvider.activeKey()
        val nonce = ByteArray(NONCE_BYTES)
        secureRandom.nextBytes(nonce)
        val characters = secret.copyValue()
        val plaintext = try {
            encodeUtf8(characters)
        } catch (exception: CharacterCodingException) {
            throw SecretEncryptionException(exception)
        } finally {
            characters.fill('\u0000')
        }

        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            keyMaterial.initializeCipher(cipher, Cipher.ENCRYPT_MODE, GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(additionalAuthenticatedData(binding, keyMaterial.keyId))
            val encrypted = cipher.doFinal(plaintext)
            val envelope = listOf(
                VERSION,
                BASE64_ENCODER.encodeToString(nonce),
                BASE64_ENCODER.encodeToString(encrypted),
            ).joinToString(ENVELOPE_SEPARATOR)
            return SecretCiphertext.of(envelope, keyMaterial.keyId)
        } catch (exception: GeneralSecurityException) {
            throw SecretEncryptionException(exception)
        } finally {
            plaintext.fill(0)
        }
    }

    override fun decrypt(ciphertext: SecretCiphertext, binding: AppSecretBinding): AppSecret {
        try {
            val envelope = decodeEnvelope(ciphertext.ciphertext)
            val keyId = ciphertext.keyId
            val keyMaterial = keyProvider.findById(keyId)
                ?.takeIf { material -> keyId == material.keyId }
                ?: throw KeyUnavailableException()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            keyMaterial.initializeCipher(
                cipher,
                Cipher.DECRYPT_MODE,
                GCMParameterSpec(TAG_BITS, envelope.nonce),
            )
            cipher.updateAAD(additionalAuthenticatedData(binding, keyMaterial.keyId))
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

    private fun additionalAuthenticatedData(binding: AppSecretBinding, keyId: String): ByteArray =
        listOf(
            "qqbot-app-secret",
            VERSION,
            keyId,
            binding.botId.toString(),
            binding.appId.value,
            binding.environment.name,
        ).joinToString("\u0000").toByteArray(StandardCharsets.UTF_8)

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
    private fun decodeUtf8(encoded: ByteArray): AppSecret {
        val buffer = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(encoded))
        val characters = CharArray(buffer.remaining())
        buffer.get(characters)
        if (buffer.hasArray()) buffer.array().fill('\u0000')
        try {
            return AppSecret.of(characters)
        } finally {
            characters.fill('\u0000')
        }
    }

    private data class Envelope(val nonce: ByteArray, val encrypted: ByteArray)

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val VERSION = "v1"
        const val ENVELOPE_SEPARATOR = "."
        const val NONCE_BYTES = 12
        const val TAG_BITS = 128
        const val TAG_BYTES = TAG_BITS / 8
        const val MAX_ENVELOPE_CHARACTERS = 16_384
        val BASE64_ENCODER: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
        val BASE64_DECODER: Base64.Decoder = Base64.getUrlDecoder()
    }
}
