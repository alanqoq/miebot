package com.mieai.qqbot.runtime.configuration

import com.mieai.qqbot.domain.bot.BotId
class BotNotFoundException(val botId: BotId) :
    RuntimeException("Bot $botId was not found")
