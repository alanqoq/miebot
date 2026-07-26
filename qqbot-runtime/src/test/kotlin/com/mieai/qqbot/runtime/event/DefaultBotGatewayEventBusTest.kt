package com.mieai.qqbot.runtime.event

import com.mieai.qqbot.domain.bot.BotId
import com.mieai.qqbot.gateway.GatewayDispatch
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class DefaultBotGatewayEventBusTest {
    @Test
    fun `delivers off the publisher thread and stops after unsubscribe`() {
        DefaultBotGatewayEventBus(8).use { bus ->
            val delivered = CountDownLatch(1)
            val threadName = AtomicReference<String>()
            val calls = AtomicInteger()
            val event = BotGatewayEvent(
                BotId.of(UUID.randomUUID()),
                GatewayDispatch(1L, "C2C_MESSAGE_CREATE", "{}"),
                Instant.parse("2026-07-22T08:00:00Z"),
            )
            val subscription = bus.subscribe {
                calls.incrementAndGet()
                threadName.set(Thread.currentThread().name)
                delivered.countDown()
            }

            bus.publish(event)
            assertThat(delivered.await(5, TimeUnit.SECONDS)).isTrue()
            assertThat(threadName.get()).startsWith("qqbot-gateway-event-source")
            subscription.close()

            bus.publish(event)
            Thread.sleep(50)
            assertThat(calls).hasValue(1)
        }
    }
}
