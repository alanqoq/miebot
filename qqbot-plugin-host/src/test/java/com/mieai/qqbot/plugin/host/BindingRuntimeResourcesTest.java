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
import java.util.concurrent.atomic.AtomicInteger;
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

        assertThat(resources.handlerIds("default", "TYPE_A")).containsExactly("first");
        assertThat(resources.handlerIds("default", "TYPE_B")).containsExactly("second");
        assertThat(resources.handlerIds("default", "TYPE_C")).isEmpty();
        resources.execute("first", event("TYPE_A"), ignored -> CompletableFuture.completedFuture(null))
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

        var execution = resources.execute("async", event("TYPE_A"), ignored -> CompletableFuture.completedFuture(null));
        resources.beginShutdown();

        assertThat(resources.awaitIdle(Duration.ofMillis(20))).isFalse();
        pending.complete(null);
        assertThat(execution.toCompletableFuture().join()).isNull();
        assertThat(resources.awaitIdle(Duration.ofSeconds(1))).isTrue();
        resources.close();
    }

    private static PluginEvent event(String type) {
        return new PluginEvent(UUID.randomUUID(), BotId.parse("550e8400-e29b-41d4-a716-446655440001"),
                BotEnvironment.SANDBOX, type, UUID.randomUUID().toString(), "{}", Instant.EPOCH,
                Optional.empty());
    }
}
