package com.mieai.qqbot.persistence.bot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SecretCiphertextTest {
    @Test
    void isAValueObjectThatAlwaysRedactsCiphertext() {
        SecretCiphertext secret = SecretCiphertext.of("v1:super-sensitive-ciphertext", "master-key-v1");

        assertThat(secret.ciphertext()).isEqualTo("v1:super-sensitive-ciphertext");
        assertThat(secret.keyId()).isEqualTo("master-key-v1");
        assertThat(secret).isEqualTo(new SecretCiphertext("v1:super-sensitive-ciphertext", "master-key-v1"));
        assertThat(secret.hashCode()).isEqualTo(
                new SecretCiphertext("v1:super-sensitive-ciphertext", "master-key-v1").hashCode());
        assertThat(secret.toString())
                .contains("<redacted>")
                .contains("master-key-v1")
                .doesNotContain("super-sensitive-ciphertext");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", " value", "value ", "value with space", "value\twith-tab"})
    void rejectsInvalidCiphertext(String value) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SecretCiphertext(value, "master-key-v1"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", " key", "key ", "key with space", "key\twith-tab"})
    void rejectsInvalidKeyId(String value) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SecretCiphertext("v1:ciphertext", value));
    }

    @Test
    void rejectsNullValues() {
        assertThatNullPointerException().isThrownBy(() -> new SecretCiphertext(null, "master-key-v1"));
        assertThatNullPointerException().isThrownBy(() -> new SecretCiphertext("v1:ciphertext", null));
    }
}
