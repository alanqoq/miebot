package com.mieai.qqbot.runtime.supervisor
import com.mieai.qqbot.persistence.bot.StoredBot
fun interface BotRuntimeFactory { fun create(configuration:StoredBot,observer:BotRuntimeObserver):ManagedBotRuntime }
