package com.mieai.qqbot.plugin.host

import com.mieai.qqbot.domain.bot.BotEnvironment
import com.mieai.qqbot.domain.bot.BotId
import com.mieai.qqbot.plugin.api.CancellationToken
import com.mieai.qqbot.plugin.api.PluginEvent
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class BindingRuntimeResourcesTest {
    @Test
    fun filtersNamedHandlersByEventTypeAndStopsAllOwnedResources() {
        val resources = BindingRuntimeResources("example", "binding", 8)
        val calls = AtomicInteger()
        val first = resources.eventService().subscribe("first", setOf("TYPE_A")) {
            calls.incrementAndGet()
            CompletableFuture.completedFuture(null)
        }
        val second = resources.eventService().subscribe("second", setOf("TYPE_B")) {
            calls.incrementAndGet()
            CompletableFuture.completedFuture(null)
        }
        val scheduled = resources.pluginScheduler().schedule(Duration.ofHours(1), calls::incrementAndGet)

        assertThat(resources.handlerIds("TYPE_A")).containsExactly("first")
        assertThat(resources.handlerIds("TYPE_B")).containsExactly("second")
        assertThat(resources.handlerIds("TYPE_C")).isEmpty()
        resources.execute("first", event("TYPE_A")).toCompletableFuture().join()
        assertThat(calls).hasValue(1)

        resources.beginShutdown()

        assertThat(first.isActive).isFalse()
        assertThat(second.isActive).isFalse()
        assertThat(scheduled.isCancelled).isTrue()
        assertThat(resources.awaitIdle(Duration.ofSeconds(1))).isTrue()
        assertThatThrownBy {
            resources.pluginScheduler().schedule(Duration.ZERO, calls::incrementAndGet)
        }.isInstanceOf(IllegalStateException::class.java)
        resources.close()
    }

    @Test
    fun waitsForAsynchronousHandlerCompletionDuringShutdown() {
        val resources = BindingRuntimeResources("example", "binding", 8)
        val pending = CompletableFuture<Void>()
        resources.eventService().subscribe("async", emptySet()) { pending }

        val execution = resources.execute("async", event("TYPE_A"))
        resources.beginShutdown()

        assertThat(resources.awaitIdle(Duration.ofMillis(20))).isFalse()
        pending.complete(null)
        assertThat(execution.toCompletableFuture().join()).isNull()
        assertThat(resources.awaitIdle(Duration.ofSeconds(1))).isTrue()
        resources.close()
    }

    @Test
    fun cancellationSignalsThePluginAndInterruptsASynchronousCallback() {
        val resources = BindingRuntimeResources("example", "cancel", 8)
        val entered = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val token = AtomicReference<CancellationToken>()

        resources.eventService().subscribe("default", emptySet()) {
            token.set(CancellationToken.current())
            entered.countDown()
            try {
                release.await(10, TimeUnit.SECONDS)
                CompletableFuture.completedFuture(null)
            } catch (_: InterruptedException) {
                interrupted.countDown()
                throw CancellationException("cancelled")
            }
        }
        val execution = resources.executeCancellable("default", event("TYPE_A"))

        assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue()
        execution.cancel()
        assertThat(execution.await(Duration.ofSeconds(1))).isTrue()
        assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue()
        assertThat(token).hasValueSatisfying { value ->
            assertThat(value.isCancellationRequested).isTrue()
        }
        resources.close()
    }

    private fun event(type: String) = PluginEvent(
        UUID.randomUUID(),
        BotId.parse("550e8400-e29b-41d4-a716-446655440001"),
        BotEnvironment.SANDBOX,
        type,
        UUID.randomUUID().toString(),
        "{}",
        Instant.EPOCH,
        null,
    )
}
