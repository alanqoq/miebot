package com.mieai.qqbot.runtime.security

import com.mieai.qqbot.domain.bot.BotEnvironment
import com.mieai.qqbot.domain.bot.BotId
import com.mieai.qqbot.domain.bot.QqAppId
import com.mieai.qqbot.persistence.bot.SecretCiphertext
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.lang.reflect.Modifier
import java.util.Arrays
import java.util.Base64
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

class AesGcmAppSecretCipherTest {
    @Test
    fun `round trips with versioned random nonce and redacted values`() {
        val cipher = cipher(0x11)
        val first: SecretCiphertext
        val second: SecretCiphertext
        AppSecret.of(PLAINTEXT).use { secret ->
            first = cipher.encrypt(secret, BINDING)
            second = cipher.encrypt(secret, BINDING)
            assertThat(secret.toString()).isEqualTo("AppSecret[<redacted>]")
        }
        assertThat(first.keyId).isEqualTo(KEY_ID)
        assertThat(first.ciphertext).startsWith("v1.").doesNotContain(PLAINTEXT)
        assertThat(second.ciphertext).isNotEqualTo(first.ciphertext)
        val envelope = first.ciphertext.split(".")
        assertThat(envelope).hasSize(3)
        assertThat(Base64.getUrlDecoder().decode(envelope[1])).hasSize(12)
        assertThat(first.toString()).contains("<redacted>").doesNotContain(PLAINTEXT)
        cipher.decrypt(first, BINDING).use { assertThat(revealForTest(it)).isEqualTo(PLAINTEXT) }
    }

    @Test
    fun `rejects tampered ciphertext without leaking plaintext`() {
        val cipher = cipher(0x22)
        val encrypted = AppSecret.of(PLAINTEXT).use { cipher.encrypt(it, BINDING) }
        assertThatThrownBy { cipher.decrypt(tamper(encrypted), BINDING) }
            .isInstanceOf(SecretDecryptionException::class.java)
            .hasMessage("Unable to decrypt AppSecret")
            .hasMessageNotContaining(PLAINTEXT)
    }

    @Test
    fun `rejects every changed aad dimension`() {
        val cipher = cipher(0x33)
        val encrypted = AppSecret.of(PLAINTEXT).use { cipher.encrypt(it, BINDING) }
        val otherBot = AppSecretBinding(BotId.parse("550e8400-e29b-41d4-a716-446655440001"), BINDING.appId, BINDING.environment)
        val otherApp = AppSecretBinding(BINDING.botId, QqAppId.of("102099999"), BINDING.environment)
        val otherEnvironment = AppSecretBinding(BINDING.botId, BINDING.appId, BotEnvironment.PRODUCTION)
        assertThatThrownBy { cipher.decrypt(encrypted, otherBot) }.isInstanceOf(SecretDecryptionException::class.java)
        assertThatThrownBy { cipher.decrypt(encrypted, otherApp) }.isInstanceOf(SecretDecryptionException::class.java)
        assertThatThrownBy { cipher.decrypt(encrypted, otherEnvironment) }.isInstanceOf(SecretDecryptionException::class.java)
    }

    @Test
    fun `rejects wrong key and reports missing key separately`() {
        val encrypted = AppSecret.of(PLAINTEXT).use { cipher(0x44).encrypt(it, BINDING) }
        assertThatThrownBy { cipher(0x45).decrypt(encrypted, BINDING) }.isInstanceOf(SecretDecryptionException::class.java)
        val unavailable = AesGcmAppSecretCipher(StaticKeyProvider.unconfigured())
        assertThatThrownBy { unavailable.decrypt(encrypted, BINDING) }
            .isInstanceOf(KeyUnavailableException::class.java)
            .hasMessage("AppSecret master key is unavailable")
        AppSecret.of(PLAINTEXT).use { secret ->
            assertThatThrownBy { unavailable.encrypt(secret, BINDING) }.isInstanceOf(KeyUnavailableException::class.java)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "v2.AAAAAAAAAAAAAAAA.AAAAAAAAAAAAAAAAAAAAAA",
        "v1.invalid.AAAAAAAAAAAAAAAAAAAAAA",
        "v1.AAAAAAAAAAAAAAAA.invalid*base64",
        "v1.AAAAAAAAAAAAAAAA",
    ])
    fun `strictly rejects malformed envelopes`(envelope: String) {
        assertThatThrownBy { cipher(0x55).decrypt(SecretCiphertext.of(envelope, KEY_ID), BINDING) }
            .isInstanceOf(SecretDecryptionException::class.java)
            .hasMessage("Unable to decrypt AppSecret")
    }

    @Test
    fun `validates key strength and never exposes key from public methods`() {
        assertThatIllegalArgumentException().isThrownBy { KeyMaterial(KEY_ID, SecretKeySpec(ByteArray(16), "AES")) }.withMessageContaining("256")
        assertThatIllegalArgumentException().isThrownBy { KeyMaterial(KEY_ID, SecretKeySpec(ByteArray(32), "HmacSHA256")) }.withMessageContaining("AES")
        val material = material(0x66)
        assertThat(material.toString()).contains("<redacted>")
        assertThat(StaticKeyProvider.configured(KEY_ID, keyBytes(0x66)).toString()).contains("configured=true").contains("<redacted>")
        assertThat(StaticKeyProvider.unconfigured().isConfigured).isFalse()
        assertThat(KeyMaterial::class.java.declaredMethods.asSequence()
            .filter { Modifier.isPublic(it.modifiers) }
            .map { it.returnType }.toList())
            .allMatch { it != ByteArray::class.java && !SecretKey::class.java.isAssignableFrom(it) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["", " ", " leading", "trailing ", "contains space", "line\nbreak"])
    fun `strictly validates app secrets`(value: String) {
        assertThatThrownBy { AppSecret.of(value) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `destroys secret memory owned by the value`() {
        val secret = AppSecret.of(PLAINTEXT)
        secret.close()
        assertThat(secret.isDestroyed).isTrue()
        assertThatThrownBy(secret::copyValue).isInstanceOf(IllegalStateException::class.java)
        assertThat(secret.toString()).doesNotContain(PLAINTEXT)
    }

    @Test
    fun `rejects malformed unicode before encryption`() {
        assertThatIllegalArgumentException().isThrownBy { AppSecret.of(charArrayOf('a', '\uD800', 'b')) }
    }

    private fun cipher(keyByte: Int) = AesGcmAppSecretCipher(StaticKeyProvider.configured(KEY_ID, keyBytes(keyByte)))
    private fun material(keyByte: Int) = KeyMaterial(KEY_ID, SecretKeySpec(keyBytes(keyByte), "AES"))
    private fun keyBytes(value: Int) = ByteArray(32) { value.toByte() }
    private fun revealForTest(secret: AppSecret): String = secret.copyValue().let { characters -> try { String(characters) } finally { characters.fill('\u0000') } }
    private fun tamper(original: SecretCiphertext): SecretCiphertext {
        val parts = original.ciphertext.split(".")
        val encrypted = Base64.getUrlDecoder().decode(parts[2])
        encrypted[0] = (encrypted[0].toInt() xor 1).toByte()
        return SecretCiphertext.of("${parts[0]}.${parts[1]}.${Base64.getUrlEncoder().withoutPadding().encodeToString(encrypted)}", original.keyId)
    }

    private companion object {
        const val KEY_ID = "master-key-v1"
        const val PLAINTEXT = "client-secret-value-123"
        val BINDING = AppSecretBinding(BotId.parse("550e8400-e29b-41d4-a716-446655440000"), QqAppId.of("102012345"), BotEnvironment.SANDBOX)
    }
}
