package com.mieai.qqbot.persistence.bot

import com.mieai.qqbot.domain.bot.BotDefinition
import com.mieai.qqbot.domain.bot.BotId
/** Complete persisted bot configuration, including an opaque encrypted AppSecret. */
data class StoredBot(
    val definition: BotDefinition,
    val appSecret: SecretCiphertext,
) {
    val id: BotId
        get() = definition.id
}
