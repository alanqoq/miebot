package com.mieai.qqbot.runtime.security
import com.mieai.qqbot.persistence.bot.SecretCiphertext
interface AppSecretCipher { fun encrypt(secret: AppSecret, binding: AppSecretBinding): SecretCiphertext; fun decrypt(ciphertext: SecretCiphertext, binding: AppSecretBinding): AppSecret }
