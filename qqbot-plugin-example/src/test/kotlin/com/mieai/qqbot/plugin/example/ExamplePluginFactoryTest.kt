package com.mieai.qqbot.plugin.example

import com.mieai.qqbot.domain.bot.BotEnvironment
import com.mieai.qqbot.plugin.api.InboundMessage
import com.mieai.qqbot.plugin.api.MessageTarget
import com.mieai.qqbot.plugin.api.MessageTargetType
import com.mieai.qqbot.plugin.api.PluginEvent
import com.mieai.qqbot.plugin.testkit.PluginTestContext
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class ExamplePluginFactoryTest {
    @Test
    fun repliesWithConfiguredContentWhenTheKeywordMatches() {
        PluginTestContext(
            "example",
            """{"_triggerKeywordComment":"Trigger explanation","triggerKeyword":"/example","_replyContentComment":"Reply explanation","replyContent":"configured reply"}""",
        ).use { fixture ->
            val factory = ExamplePluginFactory()
            val plugin = factory.create(fixture.context)

            plugin.start()
            plugin.start()
            assertThat(factory.pluginId).isEqualTo("example")
            assertThat(fixture.events.handlerIds()).containsExactly("keyword-reply")

            fixture.events.emit(event(fixture, "  /example  ")).toCompletableFuture().join()

            val reply = fixture.messages.textMessages().single()
            assertThat(reply.content).isEqualTo("configured reply")
            assertThat(reply.target).isEqualTo(MessageTarget(MessageTargetType.C2C, "user-1"))
            assertThat(reply.replyMessageId).isEqualTo("message-1")
            assertThat(reply.replyEventId).isEqualTo("event-1")

            plugin.stop()
            plugin.stop()
            assertThat(fixture.events.handlerIds()).isEmpty()
        }
    }

    @Test
    fun ignoresNonMatchingAndNonMessageEvents() {
        PluginTestContext(
            "example",
            """{"triggerKeyword":"say \"hello\"","replyContent":"matched"}""",
        ).use { fixture ->
            val plugin = ExamplePluginFactory().create(fixture.context)
            plugin.start()

            fixture.events.emit(event(fixture, "say hello")).toCompletableFuture().join()
            fixture.events.emit(event(fixture, null)).toCompletableFuture().join()
            fixture.events.emit(event(fixture, "say \"hello\"", "READY", includeMessage = false))
                .toCompletableFuture().join()
            assertThat(fixture.messages.textMessages()).isEmpty()

            fixture.events.emit(event(fixture, "say \"hello\"")).toCompletableFuture().join()
            assertThat(fixture.messages.textMessages()).hasSize(1)

            plugin.stop()
        }
    }

    @Test
    fun rejectsIncompleteOrInvalidConfiguration() {
        PluginTestContext("example", "{}").use { fixture ->
            assertThatThrownBy { ExamplePluginFactory().create(fixture.context) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("triggerKeyword and replyContent")
        }
        PluginTestContext(
            "example",
            """{"triggerKeyword":"   ","replyContent":"reply"}""",
        ).use { fixture ->
            assertThatThrownBy { ExamplePluginFactory().create(fixture.context) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("triggerKeyword")
        }
    }

    private fun event(
        fixture: PluginTestContext,
        content: String?,
        eventType: String = "C2C_MESSAGE_CREATE",
        includeMessage: Boolean = true,
    ): PluginEvent = PluginEvent(
        UUID.randomUUID(),
        fixture.context.base.botId,
        BotEnvironment.SANDBOX,
        eventType,
        "platform-${UUID.randomUUID()}",
        "{}",
        Instant.parse("2026-07-26T00:00:00Z"),
        if (includeMessage) {
            InboundMessage(
                MessageTarget(MessageTargetType.C2C, "user-1"),
                "message-1",
                "event-1",
                "user-1",
                content,
            )
        } else {
            null
        },
    )
}
