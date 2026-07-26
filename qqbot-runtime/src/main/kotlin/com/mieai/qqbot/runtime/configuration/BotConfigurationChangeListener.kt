package com.mieai.qqbot.runtime.configuration

import com.mieai.qqbot.domain.bot.BotId

fun interface BotConfigurationChangeListener {
    fun onCommitted(change: BotConfigurationChange)

    fun beforeDelete(botId: BotId) {}
    fun onDeleteAborted(botId: BotId) {}
    fun afterDelete(botId: BotId) {}

    companion object {
        fun none(): BotConfigurationChangeListener = BotConfigurationChangeListener { }

        fun composite(listeners: List<BotConfigurationChangeListener>): BotConfigurationChangeListener {
            val delegates = listeners.toList()
            return object : BotConfigurationChangeListener {
                override fun beforeDelete(botId: BotId) = invokeAll { it.beforeDelete(botId) }
                override fun onDeleteAborted(botId: BotId) = invokeAll { it.onDeleteAborted(botId) }
                override fun afterDelete(botId: BotId) = invokeAll { it.afterDelete(botId) }
                override fun onCommitted(change: BotConfigurationChange) = invokeAll { it.onCommitted(change) }
                private fun invokeAll(action: (BotConfigurationChangeListener) -> Unit) {
                    var first: RuntimeException? = null
                    for (delegate in delegates) {
                        try {
                            action(delegate)
                        } catch (failure: RuntimeException) {
                            if (first == null) first = failure else first.addSuppressed(failure)
                        }
                    }
                    if (first != null) throw first
                }
            }
        }
    }
}
