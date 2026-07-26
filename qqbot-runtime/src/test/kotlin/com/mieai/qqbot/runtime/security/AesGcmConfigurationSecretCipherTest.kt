package com.mieai.qqbot.runtime.security

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class AesGcmConfigurationSecretCipherTest {
    @Test
    fun `round trips configuration passwords including whitespace`() {
        val cipher = AesGcmConfigurationSecretCipher(StaticKeyProvider.configured("primary", keyBytes(0x41)))
        val password = " leading and trailing spaces ".toCharArray()
        val encrypted = cipher.encrypt(password, "database-password")
        val decrypted = cipher.decrypt(encrypted, "database-password")
        try {
            assertThat(decrypted).containsExactly(*password)
            assertThat(encrypted.ciphertext).doesNotContain(String(password))
            assertThat(encrypted.toString()).contains("<redacted>").doesNotContain(String(password))
        } finally {
            password.fill('\u0000')
            decrypted.fill('\u0000')
        }
    }

    @Test
    fun `binds ciphertext to its purpose`() {
        val cipher = AesGcmConfigurationSecretCipher(StaticKeyProvider.configured("primary", keyBytes(0x52)))
        val password = "database-secret".toCharArray()
        val encrypted = try {
            cipher.encrypt(password, "database-password")
        } finally {
            password.fill('\u0000')
        }

        assertThatThrownBy { cipher.decrypt(encrypted, "another-purpose") }
            .isInstanceOf(SecretDecryptionException::class.java)
            .hasMessageNotContaining("database-secret")
    }

    @Test
    fun `accepts an explicitly empty database password`() {
        val cipher = AesGcmConfigurationSecretCipher(StaticKeyProvider.configured("primary", keyBytes(0x63)))
        val decrypted = cipher.decrypt(cipher.encrypt(CharArray(0), "database-password"), "database-password")
        try {
            assertThat(decrypted).isEmpty()
        } finally {
            decrypted.fill('\u0000')
        }
    }

    private fun keyBytes(value: Int) = ByteArray(32) { value.toByte() }
}
