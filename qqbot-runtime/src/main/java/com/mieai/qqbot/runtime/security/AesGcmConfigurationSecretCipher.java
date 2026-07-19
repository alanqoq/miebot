package com.mieai.qqbot.runtime.security;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;

/** AES-256-GCM encryption for non-bot configuration secrets. */
public final class AesGcmConfigurationSecretCipher {
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String VERSION = "v1";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int TAG_BYTES = TAG_BITS / Byte.SIZE;
    private static final int MAX_ENVELOPE_CHARACTERS = 16_384;
    private static final int MAX_SECRET_CHARACTERS = 4096;
    private static final Base64.Encoder BASE64_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder BASE64_DECODER = Base64.getUrlDecoder();

    private final KeyProvider keyProvider;
    private final SecureRandom secureRandom;

    public AesGcmConfigurationSecretCipher(KeyProvider keyProvider) {
        this(keyProvider, new SecureRandom());
    }

    AesGcmConfigurationSecretCipher(KeyProvider keyProvider, SecureRandom secureRandom) {
        this.keyProvider = Objects.requireNonNull(keyProvider, "keyProvider must not be null");
        this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom must not be null");
    }

    public EncryptedConfigurationValue encrypt(char[] value, String purpose) {
        Objects.requireNonNull(value, "value must not be null");
        validateCharacters(value);
        String requiredPurpose = requirePurpose(purpose);
        KeyMaterial keyMaterial = Objects.requireNonNull(
                keyProvider.activeKey(), "keyProvider.activeKey() must not return null");
        byte[] nonce = new byte[NONCE_BYTES];
        secureRandom.nextBytes(nonce);
        byte[] plaintext;
        try {
            plaintext = encodeUtf8(value);
        } catch (CharacterCodingException exception) {
            throw new SecretEncryptionException(exception);
        }

        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, keyMaterial.key(), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(additionalAuthenticatedData(requiredPurpose, keyMaterial.keyId()));
            byte[] encrypted = cipher.doFinal(plaintext);
            String envelope = VERSION
                    + "."
                    + BASE64_ENCODER.encodeToString(nonce)
                    + "."
                    + BASE64_ENCODER.encodeToString(encrypted);
            return new EncryptedConfigurationValue(envelope, keyMaterial.keyId());
        } catch (GeneralSecurityException exception) {
            throw new SecretEncryptionException(exception);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
            Arrays.fill(nonce, (byte) 0);
        }
    }

    public char[] decrypt(EncryptedConfigurationValue value, String purpose) {
        Objects.requireNonNull(value, "value must not be null");
        String requiredPurpose = requirePurpose(purpose);
        try {
            Envelope envelope = decodeEnvelope(value.ciphertext());
            KeyMaterial keyMaterial = keyProvider.findById(value.keyId())
                    .filter(material -> value.keyId().equals(material.keyId()))
                    .orElseThrow(KeyUnavailableException::new);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, keyMaterial.key(), new GCMParameterSpec(TAG_BITS, envelope.nonce()));
            cipher.updateAAD(additionalAuthenticatedData(requiredPurpose, keyMaterial.keyId()));
            byte[] plaintext = cipher.doFinal(envelope.encrypted());
            try {
                return decodeUtf8(plaintext);
            } finally {
                Arrays.fill(plaintext, (byte) 0);
            }
        } catch (GeneralSecurityException | IllegalArgumentException | CharacterCodingException exception) {
            throw new SecretDecryptionException(exception);
        }
    }

    private static Envelope decodeEnvelope(String value) {
        if (value.length() > MAX_ENVELOPE_CHARACTERS) {
            throw new IllegalArgumentException("envelope is too long");
        }
        String[] parts = value.split("\\.", -1);
        if (parts.length != 3 || !VERSION.equals(parts[0])) {
            throw new IllegalArgumentException("unsupported envelope");
        }
        byte[] nonce = BASE64_DECODER.decode(parts[1]);
        byte[] encrypted = BASE64_DECODER.decode(parts[2]);
        if (nonce.length != NONCE_BYTES || encrypted.length < TAG_BYTES) {
            throw new IllegalArgumentException("invalid envelope length");
        }
        return new Envelope(nonce, encrypted);
    }

    private static byte[] additionalAuthenticatedData(String purpose, String keyId) {
        return String.join("\0", "qqbot-configuration-secret", VERSION, keyId, purpose)
                .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] encodeUtf8(char[] characters) throws CharacterCodingException {
        ByteBuffer buffer = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(characters));
        byte[] encoded = new byte[buffer.remaining()];
        buffer.get(encoded);
        if (buffer.hasArray()) {
            Arrays.fill(buffer.array(), (byte) 0);
        }
        return encoded;
    }

    private static char[] decodeUtf8(byte[] encoded) throws CharacterCodingException {
        CharBuffer buffer = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(encoded));
        char[] characters = new char[buffer.remaining()];
        buffer.get(characters);
        if (buffer.hasArray()) {
            Arrays.fill(buffer.array(), '\0');
        }
        validateCharacters(characters);
        return characters;
    }

    private static void validateCharacters(char[] value) {
        if (value.length > MAX_SECRET_CHARACTERS) {
            throw new IllegalArgumentException("configuration secret is too long");
        }
        for (int index = 0; index < value.length; index++) {
            char character = value[index];
            if (Character.isHighSurrogate(character)) {
                if (index + 1 >= value.length || !Character.isLowSurrogate(value[index + 1])) {
                    throw new IllegalArgumentException("configuration secret must contain valid Unicode");
                }
                index++;
            } else if (Character.isLowSurrogate(character)) {
                throw new IllegalArgumentException("configuration secret must contain valid Unicode");
            }
        }
    }

    private static String requirePurpose(String value) {
        Objects.requireNonNull(value, "purpose must not be null");
        if (value.isBlank() || !value.equals(value.strip())) {
            throw new IllegalArgumentException("purpose must be non-blank without surrounding whitespace");
        }
        return value;
    }

    private record Envelope(byte[] nonce, byte[] encrypted) {}
}
