package com.mieai.qqbot.plugin.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mieai.qqbot.domain.bot.BotEnvironment;
import com.mieai.qqbot.domain.bot.BotId;
import com.mieai.qqbot.persistence.inbox.EventInboxRepository;
import com.mieai.qqbot.persistence.inbox.InboxEvent;
import com.mieai.qqbot.persistence.inbox.InboxStatus;
import com.mieai.qqbot.persistence.plugin.BotPluginBinding;
import com.mieai.qqbot.persistence.plugin.BotPluginBindingRepository;
import com.mieai.qqbot.persistence.plugin.PluginBindingRuntimeState;
import com.mieai.qqbot.persistence.plugin.PluginDelivery;
import com.mieai.qqbot.persistence.plugin.PluginDeliveryRepository;
import com.mieai.qqbot.persistence.plugin.PluginDeliveryStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PluginRuntimeServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-20T12:00:00Z");
    private static final BotId BOT_ID = BotId.parse("550e8400-e29b-41d4-a716-446655440000");
    private static final UUID BINDING_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440001");
    private static final UUID EVENT_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440002");
    private static final UUID DELIVERY_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440003");

    @Mock Pf4jPluginHost host;
    @Mock EventInboxRepository inbox;
    @Mock BotPluginBindingRepository bindings;
    @Mock PluginDeliveryRepository deliveries;
    @Mock ScheduledExecutorService scheduler;
    @Mock ScheduledFuture<?> polling;

    @Test
    void quarantinesAndPausesABindingWhenCancellationGraceExpires() {
        BotPluginBinding binding = binding(true, PluginBindingRuntimeState.ACTIVE);
        PluginDelivery delivery = delivery();
        InboxEvent event = event();
        AtomicBoolean cancelled = new AtomicBoolean();
        PluginExecution execution = new PluginExecution() {
            private final CompletableFuture<Void> pending = new CompletableFuture<>();
            @Override public java.util.concurrent.CompletionStage<Void> stage() { return pending; }
            @Override public void cancel() { cancelled.set(true); }
            @Override public boolean isDone() { return false; }
            @Override public boolean await(Duration timeout) { return false; }
        };
        when(inbox.claimNext(anyString(), eq(NOW), any())).thenReturn(Optional.empty());
        when(deliveries.claimNext(anyString(), eq(NOW), any()))
                .thenReturn(Optional.of(delivery))
                .thenReturn(Optional.empty());
        when(bindings.findById(BINDING_ID)).thenReturn(Optional.of(binding));
        when(inbox.findById(EVENT_ID)).thenReturn(Optional.of(event));
        when(host.executeCancellable(binding, event, "handler")).thenReturn(execution);
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        when(scheduler.scheduleWithFixedDelay(task.capture(), eq(0L), eq(1L), eq(TimeUnit.MILLISECONDS)))
                .thenAnswer(ignored -> polling);

        try (PluginRuntimeService runtime = service()) {
            runtime.start();
            task.getValue().run();
        }

        assertThat(cancelled).isTrue();
        verify(host).quarantine(BINDING_ID);
        verify(bindings).setRuntimeState(eq(BINDING_ID), eq(PluginBindingRuntimeState.QUARANTINED),
                anyString(), eq(NOW));
        verify(deliveries).pauseForBinding(eq(BINDING_ID), eq(NOW), anyString());
        verify(deliveries, never()).markRetry(any(), anyLong(), any(), any(), anyString());
    }

    @Test
    void bindingChangePausesAndResumesDurableDeliveries() {
        PluginRuntimeService runtime = service();
        when(bindings.findById(BINDING_ID))
                .thenReturn(Optional.of(binding(false, PluginBindingRuntimeState.PAUSED)))
                .thenReturn(Optional.of(binding(true, PluginBindingRuntimeState.ACTIVE)));

        runtime.bindingChanged(BINDING_ID);
        runtime.bindingChanged(BINDING_ID);

        verify(host, org.mockito.Mockito.times(2)).invalidate(BINDING_ID);
        verify(deliveries).pauseForBinding(BINDING_ID, NOW, "Plugin binding is paused");
        verify(deliveries).resumeForBinding(BINDING_ID, NOW);
    }

    private PluginRuntimeService service() {
        return new PluginRuntimeService(host, inbox, bindings, deliveries, scheduler,
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMillis(1), Duration.ofSeconds(30),
                Duration.ofMillis(1), Duration.ofMillis(1), 3, 1, null, null);
    }

    private static BotPluginBinding binding(boolean enabled, PluginBindingRuntimeState state) {
        return new BotPluginBinding(BINDING_ID, "example", BOT_ID, "{}", enabled, 1L, NOW, NOW,
                state, Optional.empty());
    }

    private static PluginDelivery delivery() {
        return new PluginDelivery(DELIVERY_ID, EVENT_ID, BINDING_ID, "handler",
                PluginDeliveryStatus.IN_PROGRESS, 1, NOW, Optional.of("worker"),
                Optional.of(NOW.plusSeconds(30)), 1L, Optional.empty(), NOW, NOW, Optional.empty());
    }

    private static InboxEvent event() {
        return new InboxEvent(EVENT_ID, BotEnvironment.SANDBOX, BOT_ID, "C2C_MESSAGE_CREATE",
                "platform-event", "{}", InboxStatus.DISPATCHED, 1, NOW, Optional.empty(),
                Optional.empty(), 1L, Optional.empty(), NOW, NOW);
    }
}
