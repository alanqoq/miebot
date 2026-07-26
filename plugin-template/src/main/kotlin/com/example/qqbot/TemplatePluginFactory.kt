package com.example.qqbot

import com.mieai.qqbot.plugin.api.EventSubscription
import com.mieai.qqbot.plugin.api.PluginEvent
import com.mieai.qqbot.plugin.api.PluginRuntimeContext
import com.mieai.qqbot.plugin.api.TextMessage
import com.mieai.qqbot.plugin.spi.BotPlugin
import com.mieai.qqbot.plugin.spi.BotPluginFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/** Copy this class and replace the command logic with your plugin behavior. */
class TemplatePluginFactory : BotPluginFactory {
    override val pluginId: String = "template"

    override fun create(context: PluginRuntimeContext): BotPlugin = TemplatePlugin(context)

    private class TemplatePlugin(
        private val context: PluginRuntimeContext,
    ) : BotPlugin {
        private val subscriptions = mutableListOf<EventSubscription>()

        override fun start() {
            subscriptions += context.events.subscribe("commands", emptySet(), ::handleCommand)
            // Add more named subscriptions when a plugin has independent workflows.
            subscriptions += context.events.subscribe("audit", emptySet(), ::audit)
        }

        private fun handleCommand(event: PluginEvent): CompletionStage<Void> {
            val content = event.message?.content?.trim().orEmpty()
            if (!content.equals("/hello", ignoreCase = true)) {
                return CompletableFuture.completedFuture(null)
            }
            return context.base.messageSender.enqueue(TextMessage.reply(event, "hello"))
                .thenApply<Void> { null }
        }

        private fun audit(event: PluginEvent): CompletionStage<Void> {
            context.base.logger.info("received ${event.platformEventId}")
            return CompletableFuture.completedFuture(null)
        }

        override fun stop() {
            subscriptions.forEach(EventSubscription::close)
            subscriptions.clear()
        }
    }
}
