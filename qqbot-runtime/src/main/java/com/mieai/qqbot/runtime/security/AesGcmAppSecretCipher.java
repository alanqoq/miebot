package com.mieai.qqbot.runtime.security;

import com.mieai.qqbot.persistence.bot.SecretCiphertext;
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

/** AES-256-GCM AppSecret encryption using a strict version-one envelope. */
public final class AesGcmAppSecretCipher implements AppSecretCipher {
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String VERSION = "v1";
    private static final String ENVELOPE_SEPARATOR = ".";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int TAG_BYTES = TAG_BITS / Byte.SIZE;
    private static final int MAX_ENVELOPE_CHARACTERS = 16_384;
    private static final Base64.Encoder BASE64_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder BASE64_DECODER = Base64.getUrlDecoder();

    private final KeyProvider keyProvider;
    private final SecureRandom secureRandom;

    public AesGcmAppSecretCipher(KeyProvider keyProvider) {
        this(keyProvider, new SecureRandom());
    }

    AesGcmAppSecretCipher(KeyProvider keyProvider, SecureRandom secureRandom) {
        this.keyProvider = Objects.requireNonNull(keyProvider, "keyProvider must not be null");
        this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom must not be null");
    }

    @Override
    public SecretCiphertext encrypt(AppSecret secret, AppSecretBinding binding) {
        Objects.requireNonNull(secret, "secret must not be null");
        Objects.requireNonNull(binding, "binding must not be null");
        KeyMaterial keyMaterial = Objects.requireNonNull(
                keyProvider.activeKey(), "keyProvider.activeKey() must not return null");
        byte[] nonce = new byte[NONCE_BYTES];
        secureRandom.nextBytes(nonce);
        char[] characters = secret.copyValue();
        byte[] plaintext;
        try {
            plaintext = encodeUtf8(characters);
        } catch (CharacterCodingException exception) {
            throw new SecretEncryptionException(exception);
        } finally {
            Arrays.fill(characters, '\0');
        }

        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, keyMaterial.key(), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(additionalAuthenticatedData(binding, keyMaterial.keyId()));
            byte[] encrypted = cipher.doFinal(plaintext);
            String envelope = VERSION
                    + ENVELOPE_SEPARATOR
                    + BASE64_ENCODER.encodeToString(nonce)
                    + ENVELOPE_SEPARATOR
                    + BASE64_ENCODER.encodeToString(encrypted);
            return SecretCiphertext.of(envelope, keyMaterial.keyId());
        } catch (GeneralSecurityException exception) {
            throw new SecretEncryptionException(exception);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    @Override
    public AppSecret decrypt(SecretCiphertext ciphertext, AppSecretBinding binding) {
        Objects.requireNonNull(ciphertext, "ciphertext must not be null");
        Objects.requireNonNull(binding, "binding must not be null");

        try {
            Envelope envelope = decodeEnvelope(ciphertext.ciphertext());
            KeyMaterial keyMaterial = keyProvider.findById(ciphertext.keyId())
                    .filter(material -> ciphertext.keyId().equals(material.keyId()))
                    .orElseThrow(KeyUnavailableException::new);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    keyMaterial.key(),
                    new GCMParameterSpec(TAG_BITS, envelope.nonce()));
            cipher.updateAAD(additionalAuthenticatedData(binding, keyMaterial.keyId()));
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

    private static byte[] additionalAuthenticatedData(AppSecretBinding binding, String keyId) {
        String value = String.join(
                "\0",
                "qqbot-app-secret",
                VERSION,
                keyId,
                binding.botId().toString(),
                binding.appId().value(),
                binding.environment().name());
        return value.getBytes(StandardCharsets.UTF_8);
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

    private static AppSecret decodeUtf8(byte[] encoded) throws CharacterCodingException {
        CharBuffer buffer = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(encoded));
        char[] characters = new char[buffer.remaining()];
        buffer.get(characters);
        if (buffer.hasArray()) {
            Arrays.fill(buffer.array(), '\0');
        }
        try {
            return AppSecret.of(characters);
        } finally {
            Arrays.fill(characters, '\0');
        }
    }

    private record Envelope(byte[] nonce, byte[] encrypted) {}
}
