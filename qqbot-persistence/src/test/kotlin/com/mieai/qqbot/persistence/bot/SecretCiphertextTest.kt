package com.mieai.qqbot.persistence.bot

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class SecretCiphertextTest {
    @Test
    fun isAValueObjectThatAlwaysRedactsCiphertext() {
        val secret = SecretCiphertext.of("v1:super-sensitive-ciphertext", "master-key-v1")

        assertThat(secret.ciphertext).isEqualTo("v1:super-sensitive-ciphertext")
        assertThat(secret.keyId).isEqualTo("master-key-v1")
        assertThat(secret).isEqualTo(SecretCiphertext("v1:super-sensitive-ciphertext", "master-key-v1"))
        assertThat(secret.hashCode()).isEqualTo(
            SecretCiphertext("v1:super-sensitive-ciphertext", "master-key-v1").hashCode(),
        )
        assertThat(secret.toString())
            .contains("<redacted>")
            .contains("master-key-v1")
            .doesNotContain("super-sensitive-ciphertext")
    }

    @ParameterizedTest
    @ValueSource(strings = ["", " ", " value", "value ", "value with space", "value\twith-tab"])
    fun rejectsInvalidCiphertext(value: String) {
        assertThatIllegalArgumentException()
            .isThrownBy { SecretCiphertext(value, "master-key-v1") }
    }

    @ParameterizedTest
    @ValueSource(strings = ["", " ", " key", "key ", "key with space", "key\twith-tab"])
    fun rejectsInvalidKeyId(value: String) {
        assertThatIllegalArgumentException()
            .isThrownBy { SecretCiphertext("v1:ciphertext", value) }
    }

    @Test
    fun rejectsNullValues() {
        val constructor = SecretCiphertext::class.java.getDeclaredConstructor(String::class.java, String::class.java)
        assertThatThrownBy { constructor.newInstance(null, "master-key-v1") }
            .hasRootCauseInstanceOf(NullPointerException::class.java)
        assertThatThrownBy { constructor.newInstance("v1:ciphertext", null) }
            .hasRootCauseInstanceOf(NullPointerException::class.java)
    }
}
