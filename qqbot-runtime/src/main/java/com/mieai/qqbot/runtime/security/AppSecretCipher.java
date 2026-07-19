package com.mieai.qqbot.runtime.security;

import com.mieai.qqbot.persistence.bot.SecretCiphertext;

/** Encryption boundary for AppSecret persistence. */
public interface AppSecretCipher {
    SecretCiphertext encrypt(AppSecret secret, AppSecretBinding binding);

    AppSecret decrypt(SecretCiphertext ciphertext, AppSecretBinding binding);
}
