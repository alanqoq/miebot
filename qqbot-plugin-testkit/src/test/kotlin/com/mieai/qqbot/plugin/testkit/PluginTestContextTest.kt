package com.mieai.qqbot.plugin.testkit

import com.mieai.qqbot.plugin.api.MessageDeliveryState
import com.mieai.qqbot.plugin.api.MessageReference
import com.mieai.qqbot.plugin.api.MessageSendOptions
import com.mieai.qqbot.plugin.api.MessageTarget
import com.mieai.qqbot.plugin.api.MessageTargetType
import com.mieai.qqbot.plugin.api.TextMessage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger

class PluginTestContextTest {
    @Test
    fun exposesIsolatedStorageAndControllableScheduler() {
        PluginTestContext("example", "{}").use { fixture ->
            fixture.storage.put("state", "key", "value")
            val calls = AtomicInteger()
            fixture.scheduler.schedule(Duration.ofSeconds(2), calls::incrementAndGet)

            fixture.scheduler.advance(Duration.ofSeconds(1))
            assertThat(calls).hasValue(0)
            fixture.scheduler.advance(Duration.ofSeconds(1))

            assertThat(calls).hasValue(1)
            assertThat(fixture.storage.get("state", "key")).contains("value")
        }
    }

    @Test
    fun closesEveryEventSubscriptionWithoutConcurrentModification() {
        PluginTestContext("example", "{}").use { fixture ->
            val first = fixture.events.subscribe("first", emptySet()) {
                CompletableFuture.completedFuture(null)
            }
            val second = fixture.events.subscribe("second", emptySet()) {
                CompletableFuture.completedFuture(null)
            }

            fixture.events.close()

            assertThat(first.isActive).isFalse()
            assertThat(second.isActive).isFalse()
            assertThat(fixture.events.handlerIds()).isEmpty()
        }
    }

    @Test
    fun simulatesPersistentMessageDeliveryReceipts() {
        PluginTestContext("example", "{}").use { fixture ->
            val jobId = UUID.fromString("750e8400-e29b-41d4-a716-446655440001")

            fixture.messages.succeed(jobId, "bot-message-900")

            val receipt = requireNotNull(fixture.messages.findDelivery(jobId).toCompletableFuture().join())
            assertThat(receipt.platformMessageId).isEqualTo("bot-message-900")
        }
    }

    @Test
    fun exposesPendingDeliveryImmediatelyAfterEnqueue() {
        PluginTestContext("example", "{}").use { fixture ->
            val queued = fixture.messages.enqueue(
                TextMessage(
                    MessageTarget(MessageTargetType.GROUP, "group-1"),
                    "hello",
                ),
            ).toCompletableFuture().join()

            val receipt = requireNotNull(fixture.messages.findDelivery(queued.jobId).toCompletableFuture().join())
            assertThat(receipt.state).isEqualTo(MessageDeliveryState.PENDING)
        }
    }

    @Test
    fun recordsExplicitMessageReferenceOptions() {
        PluginTestContext("example", "{}").use { fixture ->
            val options = MessageSendOptions(MessageReference("bot-message-900", true))

            fixture.messages.enqueue(
                TextMessage(MessageTarget(MessageTargetType.GROUP, "group-1"), "hello"),
                options,
            ).toCompletableFuture().join()

            assertThat(fixture.messages.textSendOptions()).containsExactly(options)
        }
    }
}
