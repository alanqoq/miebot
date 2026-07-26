package com.mieai.qqbot.runtime.configuration

import com.mieai.qqbot.domain.bot.BotId
import com.mieai.qqbot.domain.bot.BotRevision

data class BotConfigurationChange(val botId: BotId, val revision: BotRevision, val enabled: Boolean, val kind: BotConfigurationChangeKind) {
    init {
        require(!(enabled && kind == BotConfigurationChangeKind.DISABLED)) { "a disabled change must not be enabled" }
        require(!(!enabled && kind == BotConfigurationChangeKind.ENABLED)) { "an enabled change must be enabled" }
    }
}
