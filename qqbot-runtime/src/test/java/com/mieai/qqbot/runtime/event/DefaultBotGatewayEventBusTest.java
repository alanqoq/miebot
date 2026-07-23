package com.mieai.qqbot.runtime.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.mieai.qqbot.domain.bot.BotId;
import com.mieai.qqbot.gateway.GatewayDispatch;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DefaultBotGatewayEventBusTest {
    @Test
    void deliversOffThePublisherThreadAndStopsAfterUnsubscribe() throws Exception {
        try (DefaultBotGatewayEventBus bus = new DefaultBotGatewayEventBus(8)) {
            CountDownLatch delivered = new CountDownLatch(1);
            AtomicReference<String> threadName = new AtomicReference<>();
            AtomicInteger calls = new AtomicInteger();
            BotGatewayEvent event = new BotGatewayEvent(
                    BotId.of(UUID.randomUUID()),
                    new GatewayDispatch(1L, "C2C_MESSAGE_CREATE", "{}"),
                    Instant.parse("2026-07-22T08:00:00Z"));
            AutoCloseable subscription = bus.subscribe(value -> {
                calls.incrementAndGet();
                threadName.set(Thread.currentThread().getName());
                delivered.countDown();
            });

            bus.publish(event);
            assertThat(delivered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(threadName.get()).startsWith("qqbot-gateway-event-source");
            subscription.close();

            bus.publish(event);
            Thread.sleep(50L);
            assertThat(calls).hasValue(1);
        }
    }
}
