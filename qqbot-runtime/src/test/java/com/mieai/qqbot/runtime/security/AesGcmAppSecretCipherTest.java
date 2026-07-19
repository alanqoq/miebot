package com.mieai.qqbot.runtime.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mieai.qqbot.domain.bot.BotEnvironment;
import com.mieai.qqbot.domain.bot.BotId;
import com.mieai.qqbot.domain.bot.QqAppId;
import com.mieai.qqbot.persistence.bot.SecretCiphertext;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AesGcmAppSecretCipherTest {
    private static final String KEY_ID = "master-key-v1";
    private static final String PLAINTEXT = "client-secret-value-123";
    private static final AppSecretBinding BINDING = new AppSecretBinding(
            BotId.parse("550e8400-e29b-41d4-a716-446655440000"),
            QqAppId.of("102012345"),
            BotEnvironment.SANDBOX);

    @Test
    void roundTripsWithVersionedRandomNonceAndRedactedValues() {
        AesGcmAppSecretCipher cipher = cipher((byte) 0x11);

        SecretCiphertext first;
        SecretCiphertext second;
        try (AppSecret secret = AppSecret.of(PLAINTEXT)) {
            first = cipher.encrypt(secret, BINDING);
            second = cipher.encrypt(secret, BINDING);
            assertThat(secret.toString()).isEqualTo("AppSecret[<redacted>]");
        }

        assertThat(first.keyId()).isEqualTo(KEY_ID);
        assertThat(first.ciphertext()).startsWith("v1.").doesNotContain(PLAINTEXT);
        assertThat(second.ciphertext()).isNotEqualTo(first.ciphertext());
        String[] envelope = first.ciphertext().split("\\.");
        assertThat(envelope).hasSize(3);
        assertThat(Base64.getUrlDecoder().decode(envelope[1])).hasSize(12);
        assertThat(first.toString()).contains("<redacted>").doesNotContain(PLAINTEXT);

        try (AppSecret decrypted = cipher.decrypt(first, BINDING)) {
            assertThat(revealForTest(decrypted)).isEqualTo(PLAINTEXT);
        }
    }

    @Test
    void rejectsTamperedCiphertextWithoutLeakingPlaintext() {
        AesGcmAppSecretCipher cipher = cipher((byte) 0x22);
        SecretCiphertext encrypted;
        try (AppSecret secret = AppSecret.of(PLAINTEXT)) {
            encrypted = cipher.encrypt(secret, BINDING);
        }

        SecretCiphertext tampered = tamper(encrypted);

        assertThatThrownBy(() -> cipher.decrypt(tampered, BINDING))
                .isInstanceOf(SecretDecryptionException.class)
                .hasMessage("Unable to decrypt AppSecret")
                .hasMessageNotContaining(PLAINTEXT);
    }

    @Test
    void rejectsEveryChangedAadDimension() {
        AesGcmAppSecretCipher cipher = cipher((byte) 0x33);
        SecretCiphertext encrypted;
        try (AppSecret secret = AppSecret.of(PLAINTEXT)) {
            encrypted = cipher.encrypt(secret, BINDING);
        }

        AppSecretBinding otherBot = new AppSecretBinding(
                BotId.parse("550e8400-e29b-41d4-a716-446655440001"),
                BINDING.appId(),
                BINDING.environment());
        AppSecretBinding otherApp = new AppSecretBinding(
                BINDING.botId(), QqAppId.of("102099999"), BINDING.environment());
        AppSecretBinding otherEnvironment = new AppSecretBinding(
                BINDING.botId(), BINDING.appId(), BotEnvironment.PRODUCTION);

        assertThatThrownBy(() -> cipher.decrypt(encrypted, otherBot))
                .isInstanceOf(SecretDecryptionException.class);
        assertThatThrownBy(() -> cipher.decrypt(encrypted, otherApp))
                .isInstanceOf(SecretDecryptionException.class);
        assertThatThrownBy(() -> cipher.decrypt(encrypted, otherEnvironment))
                .isInstanceOf(SecretDecryptionException.class);
    }

    @Test
    void rejectsWrongKeyAndReportsMissingKeySeparately() {
        AesGcmAppSecretCipher encryptor = cipher((byte) 0x44);
        SecretCiphertext encrypted;
        try (AppSecret secret = AppSecret.of(PLAINTEXT)) {
            encrypted = encryptor.encrypt(secret, BINDING);
        }

        AesGcmAppSecretCipher wrongKey = cipher((byte) 0x45);
        assertThatThrownBy(() -> wrongKey.decrypt(encrypted, BINDING))
                .isInstanceOf(SecretDecryptionException.class);

        AesGcmAppSecretCipher unavailable = new AesGcmAppSecretCipher(StaticKeyProvider.unconfigured());
        assertThatThrownBy(() -> unavailable.decrypt(encrypted, BINDING))
                .isInstanceOf(KeyUnavailableException.class)
                .hasMessage("AppSecret master key is unavailable");
        try (AppSecret secret = AppSecret.of(PLAINTEXT)) {
            assertThatThrownBy(() -> unavailable.encrypt(secret, BINDING))
                    .isInstanceOf(KeyUnavailableException.class);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "v2.AAAAAAAAAAAAAAAA.AAAAAAAAAAAAAAAAAAAAAA",
        "v1.invalid.AAAAAAAAAAAAAAAAAAAAAA",
        "v1.AAAAAAAAAAAAAAAA.invalid*base64",
        "v1.AAAAAAAAAAAAAAAA"
    })
    void strictlyRejectsMalformedEnvelopes(String envelope) {
        AesGcmAppSecretCipher cipher = cipher((byte) 0x55);
        SecretCiphertext malformed = SecretCiphertext.of(envelope, KEY_ID);

        assertThatThrownBy(() -> cipher.decrypt(malformed, BINDING))
                .isInstanceOf(SecretDecryptionException.class)
                .hasMessage("Unable to decrypt AppSecret");
    }

    @Test
    void validatesKeyStrengthAndNeverExposesKeyFromPublicMethods() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new KeyMaterial(KEY_ID, new SecretKeySpec(new byte[16], "AES")))
                .withMessageContaining("256");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new KeyMaterial(KEY_ID, new SecretKeySpec(new byte[32], "HmacSHA256")))
                .withMessageContaining("AES");

        KeyMaterial material = material((byte) 0x66);
        assertThat(material.toString()).contains("<redacted>");
        assertThat(StaticKeyProvider.configured(KEY_ID, keyBytes((byte) 0x66)).toString())
                .contains("configured=true")
                .contains("<redacted>");
        assertThat(StaticKeyProvider.unconfigured().isConfigured()).isFalse();
        assertThat(Arrays.stream(KeyMaterial.class.getDeclaredMethods())
                        .filter(method -> Modifier.isPublic(method.getModifiers()))
                        .map(method -> method.getReturnType()))
                .allMatch(type -> type != byte[].class && !SecretKey.class.isAssignableFrom(type));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", " leading", "trailing ", "contains space", "line\nbreak"})
    void strictlyValidatesAppSecrets(String value) {
        assertThatThrownBy(() -> AppSecret.of(value)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void destroysSecretMemoryOwnedByTheValue() {
        AppSecret secret = AppSecret.of(PLAINTEXT);

        secret.close();

        assertThat(secret.isDestroyed()).isTrue();
        assertThatThrownBy(secret::copyValue).isInstanceOf(IllegalStateException.class);
        assertThat(secret.toString()).doesNotContain(PLAINTEXT);
    }

    @Test
    void rejectsMalformedUnicodeBeforeEncryption() {
        char[] malformed = {'a', '\uD800', 'b'};

        assertThatIllegalArgumentException().isThrownBy(() -> AppSecret.of(malformed));
    }

    private static AesGcmAppSecretCipher cipher(byte keyByte) {
        return new AesGcmAppSecretCipher(StaticKeyProvider.configured(KEY_ID, keyBytes(keyByte)));
    }

    private static KeyMaterial material(byte keyByte) {
        return new KeyMaterial(KEY_ID, new SecretKeySpec(keyBytes(keyByte), "AES"));
    }

    private static byte[] keyBytes(byte value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, value);
        return bytes;
    }

    private static String revealForTest(AppSecret secret) {
        char[] characters = secret.copyValue();
        try {
            return new String(characters);
        } finally {
            Arrays.fill(characters, '\0');
        }
    }

    private static SecretCiphertext tamper(SecretCiphertext original) {
        String[] parts = original.ciphertext().split("\\.");
        byte[] encrypted = Base64.getUrlDecoder().decode(parts[2]);
        encrypted[0] ^= 1;
        String changed = parts[0]
                + "."
                + parts[1]
                + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(encrypted);
        return SecretCiphertext.of(changed, original.keyId());
    }
}
