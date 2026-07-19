package com.mieai.qqbot.runtime.supervisor;

import com.mieai.qqbot.domain.bot.BotDefinition;

@FunctionalInterface
interface RuntimeSchedulerFactory {
    RuntimeScheduler create(BotDefinition definition);
}
