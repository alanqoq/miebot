package com.mieai.qqbot.runtime.security;

import com.mieai.qqbot.client.BotCredentials;
import com.mieai.qqbot.persistence.bot.StoredBot;
import java.util.Arrays;
import java.util.Objects;

/** Transfers one encrypted persisted credential into the client runtime without exposing plaintext. */
public final class BotCredentialDecryptor {
    private final AppSecretCipher cipher;

    public BotCredentialDecryptor(AppSecretCipher cipher) {
        this.cipher = Objects.requireNonNull(cipher, "cipher must not be null");
    }

    public BotCredentials decrypt(StoredBot storedBot) {
        Objects.requireNonNull(storedBot, "storedBot must not be null");
        var definition = storedBot.definition();
        AppSecretBinding binding = new AppSecretBinding(
                definition.id(), definition.appId(), definition.environment());
        try (AppSecret decrypted = Objects.requireNonNull(
                cipher.decrypt(storedBot.appSecret(), binding), "cipher returned null")) {
            char[] plaintext = decrypted.copyValue();
            try {
                com.mieai.qqbot.client.AppSecret clientSecret =
                        com.mieai.qqbot.client.AppSecret.of(plaintext);
                try {
                    return new BotCredentials(definition.appId(), clientSecret);
                } catch (RuntimeException exception) {
                    clientSecret.close();
                    throw exception;
                }
            } finally {
                Arrays.fill(plaintext, '\0');
            }
        }
    }
}
