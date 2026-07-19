package com.mieai.qqbot.runtime.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class AesGcmConfigurationSecretCipherTest {

    @Test
    void roundTripsConfigurationPasswordsIncludingWhitespace() {
        AesGcmConfigurationSecretCipher cipher = new AesGcmConfigurationSecretCipher(
                StaticKeyProvider.configured("primary", keyBytes((byte) 0x41)));
        char[] password = " leading and trailing spaces ".toCharArray();

        EncryptedConfigurationValue encrypted = cipher.encrypt(password, "database-password");
        char[] decrypted = cipher.decrypt(encrypted, "database-password");
        try {
            assertThat(decrypted).containsExactly(password);
            assertThat(encrypted.ciphertext()).doesNotContain(new String(password));
            assertThat(encrypted.toString()).contains("<redacted>").doesNotContain(new String(password));
        } finally {
            Arrays.fill(password, '\0');
            Arrays.fill(decrypted, '\0');
        }
    }

    @Test
    void bindsCiphertextToItsPurpose() {
        AesGcmConfigurationSecretCipher cipher = new AesGcmConfigurationSecretCipher(
                StaticKeyProvider.configured("primary", keyBytes((byte) 0x52)));
        char[] password = "database-secret".toCharArray();
        EncryptedConfigurationValue encrypted;
        try {
            encrypted = cipher.encrypt(password, "database-password");
        } finally {
            Arrays.fill(password, '\0');
        }

        assertThatThrownBy(() -> cipher.decrypt(encrypted, "another-purpose"))
                .isInstanceOf(SecretDecryptionException.class)
                .hasMessageNotContaining("database-secret");
    }

    @Test
    void acceptsAnExplicitlyEmptyDatabasePassword() {
        AesGcmConfigurationSecretCipher cipher = new AesGcmConfigurationSecretCipher(
                StaticKeyProvider.configured("primary", keyBytes((byte) 0x63)));

        EncryptedConfigurationValue encrypted = cipher.encrypt(new char[0], "database-password");
        char[] decrypted = cipher.decrypt(encrypted, "database-password");
        try {
            assertThat(decrypted).isEmpty();
        } finally {
            Arrays.fill(decrypted, '\0');
        }
    }

    private static byte[] keyBytes(byte value) {
        byte[] key = new byte[32];
        Arrays.fill(key, value);
        return key;
    }
}
