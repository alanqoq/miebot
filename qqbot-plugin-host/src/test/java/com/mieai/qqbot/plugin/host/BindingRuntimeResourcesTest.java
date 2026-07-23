package com.mieai.qqbot.plugin.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mieai.qqbot.domain.bot.BotEnvironment;
import com.mieai.qqbot.domain.bot.BotId;
import com.mieai.qqbot.plugin.api.PluginEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class BindingRuntimeResourcesTest {
    @Test
    void filtersNamedHandlersByEventTypeAndStopsAllOwnedResources() {
        BindingRuntimeResources resources = new BindingRuntimeResources("example", "binding", 8);
        AtomicInteger calls = new AtomicInteger();
        var first = resources.eventService().subscribe("first", Set.of("TYPE_A"), ignored -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        });
        var second = resources.eventService().subscribe("second", Set.of("TYPE_B"), ignored -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        });
        var scheduled = resources.pluginScheduler().schedule(Duration.ofHours(1), calls::incrementAndGet);

        assertThat(resources.handlerIds("TYPE_A")).containsExactly("first");
        assertThat(resources.handlerIds("TYPE_B")).containsExactly("second");
        assertThat(resources.handlerIds("TYPE_C")).isEmpty();
        resources.execute("first", event("TYPE_A"))
                .toCompletableFuture().join();
        assertThat(calls).hasValue(1);

        resources.beginShutdown();

        assertThat(first.isActive()).isFalse();
        assertThat(second.isActive()).isFalse();
        assertThat(scheduled.isCancelled()).isTrue();
        assertThat(resources.awaitIdle(Duration.ofSeconds(1))).isTrue();
        assertThatThrownBy(() -> resources.pluginScheduler()
                .schedule(Duration.ZERO, calls::incrementAndGet))
                .isInstanceOf(IllegalStateException.class);
        resources.close();
    }

    @Test
    void waitsForAsynchronousHandlerCompletionDuringShutdown() {
        BindingRuntimeResources resources = new BindingRuntimeResources("example", "binding", 8);
        CompletableFuture<Void> pending = new CompletableFuture<>();
        resources.eventService().subscribe("async", Set.of(), ignored -> pending);

        var execution = resources.execute("async", event("TYPE_A"));
        resources.beginShutdown();

        assertThat(resources.awaitIdle(Duration.ofMillis(20))).isFalse();
        pending.complete(null);
        assertThat(execution.toCompletableFuture().join()).isNull();
        assertThat(resources.awaitIdle(Duration.ofSeconds(1))).isTrue();
        resources.close();
    }

    @Test
    void cancellationSignalsThePluginAndInterruptsASynchronousCallback() throws Exception {
        BindingRuntimeResources resources = new BindingRuntimeResources("example", "cancel", 8);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<com.mieai.qqbot.plugin.api.CancellationToken> token = new AtomicReference<>();

        resources.eventService().subscribe("default", Set.of(), ignored -> {
            token.set(com.mieai.qqbot.plugin.api.CancellationToken.current());
            entered.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
                return CompletableFuture.completedFuture(null);
            } catch (InterruptedException exception) {
                interrupted.countDown();
                throw new CancellationException("cancelled");
            }
        });
        PluginExecution execution = resources.executeCancellable("default", event("TYPE_A"));

        assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
        execution.cancel();
        assertThat(execution.await(Duration.ofSeconds(1))).isTrue();
        assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(token).hasValueSatisfying(value -> assertThat(value.isCancellationRequested()).isTrue());
        resources.close();
    }

    private static PluginEvent event(String type) {
        return new PluginEvent(UUID.randomUUID(), BotId.parse("550e8400-e29b-41d4-a716-446655440001"),
                BotEnvironment.SANDBOX, type, UUID.randomUUID().toString(), "{}", Instant.EPOCH,
                Optional.empty());
    }
}
