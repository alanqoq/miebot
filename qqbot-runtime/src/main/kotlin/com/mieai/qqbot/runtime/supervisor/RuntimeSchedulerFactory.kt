package com.mieai.qqbot.runtime.supervisor
import com.mieai.qqbot.domain.bot.BotDefinition
fun interface RuntimeSchedulerFactory { fun create(definition:BotDefinition):RuntimeScheduler }
