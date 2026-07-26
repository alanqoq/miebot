package com.mieai.qqbot.plugin.example

import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.mieai.qqbot.plugin.api.EventSubscription
import com.mieai.qqbot.plugin.api.PluginEvent
import com.mieai.qqbot.plugin.api.PluginRuntimeContext
import com.mieai.qqbot.plugin.api.TextMessage
import com.mieai.qqbot.plugin.spi.BotPlugin
import com.mieai.qqbot.plugin.spi.BotPluginFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/** Configurable keyword reply plugin used as the platform's runnable example. */
class ExamplePluginFactory : BotPluginFactory {
    override val pluginId: String = "example"

    override fun create(context: PluginRuntimeContext): BotPlugin =
        ExamplePlugin(context, ExampleConfiguration.parse(context.configuration.json))

    private class ExamplePlugin(
        private val context: PluginRuntimeContext,
        private val configuration: ExampleConfiguration,
    ) : BotPlugin {
        private var subscription: EventSubscription? = null

        override fun start() {
            if (subscription?.isActive == true) return
            subscription = context.events.subscribe("keyword-reply", MESSAGE_EVENT_TYPES, ::handleMessage)
        }

        private fun handleMessage(event: PluginEvent): CompletionStage<Void> {
            val content = event.message?.content?.trim()
            if (content != configuration.triggerKeyword) {
                return CompletableFuture.completedFuture(null)
            }
            return context.base.messageSender.enqueue(TextMessage.reply(event, configuration.replyContent))
                .thenApply<Void> { null }
        }

        override fun stop() {
            subscription?.close()
            subscription = null
        }
    }

    private data class ExampleConfiguration(
        val triggerKeyword: String,
        val replyContent: String,
    ) {
        companion object {
            private val FIELDS = setOf("triggerKeyword", "replyContent")

            fun parse(json: String): ExampleConfiguration {
                val root = try {
                    JsonParser.parseString(json)
                } catch (exception: JsonParseException) {
                    throw IllegalArgumentException("Example plugin configuration is not valid JSON", exception)
                }
                require(root.isJsonObject) { "Example plugin configuration must be a JSON object" }
                val value = root.asJsonObject
                require(value.keySet() == FIELDS) {
                    "Example plugin configuration must contain only triggerKeyword and replyContent"
                }

                val triggerKeyword = value.string("triggerKeyword").trim()
                val replyContent = value.string("replyContent")
                require(triggerKeyword.isNotEmpty() && triggerKeyword.codePointCount(0, triggerKeyword.length) <= 256) {
                    "triggerKeyword must contain 1 to 256 characters"
                }
                require(triggerKeyword.codePoints().noneMatch(Character::isISOControl)) {
                    "triggerKeyword must not contain control characters"
                }
                require(replyContent.isNotBlank() && replyContent.codePointCount(0, replyContent.length) <= 4000) {
                    "replyContent must contain 1 to 4000 characters"
                }
                require(replyContent.codePoints().noneMatch(::unsupportedReplyControl)) {
                    "replyContent contains an unsupported control character"
                }
                return ExampleConfiguration(triggerKeyword, replyContent)
            }

            private fun JsonObject.string(name: String): String {
                val value = get(name)
                require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString) {
                    "$name must be a string"
                }
                return value.asString
            }

            private fun unsupportedReplyControl(value: Int): Boolean =
                Character.isISOControl(value) && value != '\n'.code && value != '\r'.code && value != '\t'.code
        }
    }

    private companion object {
        val MESSAGE_EVENT_TYPES = setOf(
            "MESSAGE_CREATE",
            "AT_MESSAGE_CREATE",
            "DIRECT_MESSAGE_CREATE",
            "GROUP_AT_MESSAGE_CREATE",
            "GROUP_MESSAGE_CREATE",
            "C2C_MESSAGE_CREATE",
        )
    }
}
