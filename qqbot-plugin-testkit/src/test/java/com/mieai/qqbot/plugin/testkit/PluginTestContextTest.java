package com.mieai.qqbot.plugin.testkit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PluginTestContextTest {
    @Test
    void exposesIsolatedStorageAndControllableScheduler() {
        try (PluginTestContext fixture = new PluginTestContext("example", "{}")) {
            fixture.storage().put("state", "key", "value");
            AtomicInteger calls = new AtomicInteger();
            fixture.scheduler().schedule(Duration.ofSeconds(2), calls::incrementAndGet);

            fixture.scheduler().advance(Duration.ofSeconds(1));
            assertThat(calls).hasValue(0);
            fixture.scheduler().advance(Duration.ofSeconds(1));

            assertThat(calls).hasValue(1);
            assertThat(fixture.storage().get("state", "key")).contains("value");
        }
    }

    @Test
    void closesEveryEventSubscriptionWithoutConcurrentModification() {
        try (PluginTestContext fixture = new PluginTestContext("example", "{}")) {
            var first = fixture.events().subscribe("first", Set.of(), event ->
                    java.util.concurrent.CompletableFuture.completedFuture(null));
            var second = fixture.events().subscribe("second", Set.of(), event ->
                    java.util.concurrent.CompletableFuture.completedFuture(null));

            fixture.events().close();

            assertThat(first.isActive()).isFalse();
            assertThat(second.isActive()).isFalse();
            assertThat(fixture.events().handlerIds()).isEmpty();
        }
    }
}
