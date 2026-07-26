package com.mieai.qqbot.runtime.security
import com.mieai.qqbot.client.*
import com.mieai.qqbot.persistence.bot.StoredBot
class BotCredentialDecryptor(private val cipher: AppSecretCipher) {
    fun decrypt(storedBot: StoredBot): BotCredentials { val d=storedBot.definition; val binding=AppSecretBinding(d.id,d.appId,d.environment); cipher.decrypt(storedBot.appSecret,binding).use { decrypted -> val plaintext=decrypted.copyValue(); try { val clientSecret=com.mieai.qqbot.client.AppSecret.of(plaintext); try { return BotCredentials(d.appId,clientSecret) } catch(e:RuntimeException){ clientSecret.close(); throw e } } finally { plaintext.fill('\u0000') } } }
}
