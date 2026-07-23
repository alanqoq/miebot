package com.mieai.qqbot.plugin.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mieai.qqbot.domain.bot.BotEnvironment;
import com.mieai.qqbot.domain.bot.BotId;
import com.mieai.qqbot.persistence.inbox.EventInboxRepository;
import com.mieai.qqbot.persistence.inbox.InboxEvent;
import com.mieai.qqbot.persistence.inbox.InboxStatus;
import com.mieai.qqbot.persistence.lease.BotLeaseRepository;
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
import java.util.List;
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
import org.mockito.InOrder;
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
    @Mock BotLeaseRepository botLeases;
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

    @Test
    void reconcilesAuthoritativeBindingsBeforeMaterializingOrDelivering() {
        BotPluginBinding binding = binding(true, PluginBindingRuntimeState.ACTIVE);
        when(bindings.findAll()).thenReturn(List.of(binding));
        when(inbox.claimNext(anyString(), eq(NOW), any())).thenReturn(Optional.empty());
        when(deliveries.claimNext(anyString(), eq(NOW), any())).thenReturn(Optional.empty());
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        when(scheduler.scheduleWithFixedDelay(task.capture(), eq(0L), eq(1L), eq(TimeUnit.MILLISECONDS)))
                .thenAnswer(ignored -> polling);

        try (PluginRuntimeService runtime = service()) {
            runtime.start();
            task.getValue().run();
        }

        InOrder order = inOrder(bindings, host, inbox, deliveries);
        order.verify(bindings).findAll();
        order.verify(host).reconcileBindings(List.of(binding));
        order.verify(inbox).claimNext(anyString(), eq(NOW), any());
        order.verify(deliveries).claimNext(anyString(), eq(NOW), any());
    }

    @Test
    void excludesBindingsAfterLeaseLossAndChecksEachBotOnlyOnce() {
        BotPluginBinding first = binding(true, PluginBindingRuntimeState.ACTIVE);
        BotPluginBinding second = new BotPluginBinding(UUID.randomUUID(), "example", BOT_ID,
                true, 1L, NOW, NOW, PluginBindingRuntimeState.ACTIVE, Optional.empty());
        when(bindings.findAll()).thenReturn(List.of(first, second));
        when(botLeases.isOwned(BOT_ID, "instance-a", NOW)).thenReturn(false);
        when(inbox.claimNextOwned(anyString(), eq("instance-a"), eq(NOW), any()))
                .thenReturn(Optional.empty());
        when(deliveries.claimNextOwned(anyString(), eq("instance-a"), eq(NOW), any()))
                .thenReturn(Optional.empty());
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        when(scheduler.scheduleWithFixedDelay(task.capture(), eq(0L), eq(1L), eq(TimeUnit.MILLISECONDS)))
                .thenAnswer(ignored -> polling);

        try (PluginRuntimeService runtime = service(botLeases, "instance-a")) {
            runtime.start();
            task.getValue().run();
        }

        verify(botLeases).isOwned(BOT_ID, "instance-a", NOW);
        verify(host).reconcileBindings(List.of());
    }

    @Test
    void invalidatesAllAndSkipsWorkWhenBindingOrLeaseReconciliationFails() {
        BotPluginBinding binding = binding(true, PluginBindingRuntimeState.ACTIVE);
        when(bindings.findAll())
                .thenReturn(List.of(binding))
                .thenThrow(new IllegalStateException("database unavailable"));
        when(botLeases.isOwned(BOT_ID, "instance-a", NOW))
                .thenThrow(new IllegalStateException("lease unavailable"));
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        when(scheduler.scheduleWithFixedDelay(task.capture(), eq(0L), eq(1L), eq(TimeUnit.MILLISECONDS)))
                .thenAnswer(ignored -> polling);

        try (PluginRuntimeService runtime = service(botLeases, "instance-a")) {
            runtime.start();
            task.getValue().run();
            task.getValue().run();
        }

        verify(host, org.mockito.Mockito.times(2)).invalidateAll();
        verify(host, never()).reconcileBindings(any());
        verify(inbox, never()).claimNextOwned(anyString(), anyString(), any(), any());
        verify(deliveries, never()).claimNextOwned(anyString(), anyString(), any(), any());
    }

    @Test
    void keepsBotBlockedBetweenSuccessfulDeletionPreparationAndItsOutcome() {
        BotPluginBinding binding = binding(true, PluginBindingRuntimeState.ACTIVE);
        when(bindings.findAll()).thenReturn(List.of(binding));
        when(inbox.claimNext(anyString(), eq(NOW), any())).thenReturn(Optional.empty());
        when(deliveries.claimNext(anyString(), eq(NOW), any())).thenReturn(Optional.empty());
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        when(scheduler.scheduleWithFixedDelay(task.capture(), eq(0L), eq(1L), eq(TimeUnit.MILLISECONDS)))
                .thenAnswer(ignored -> polling);

        try (PluginRuntimeService runtime = service()) {
            runtime.start();
            runtime.beforeBotDeletion(BOT_ID);
            task.getValue().run();
            runtime.botDeletionAborted(BOT_ID);
            task.getValue().run();
        }

        verify(host).invalidateBot(BOT_ID);
        ArgumentCaptor<List<BotPluginBinding>> reconciliations = ArgumentCaptor.forClass(List.class);
        verify(host, org.mockito.Mockito.times(2)).reconcileBindings(reconciliations.capture());
        assertThat(reconciliations.getAllValues()).containsExactly(List.of(), List.of(binding));
    }

    @Test
    void quiescenceFailureRemainsFailClosedAfterDeleteAbort() {
        BotPluginBinding binding = binding(true, PluginBindingRuntimeState.ACTIVE);
        doThrow(new IllegalStateException("callback still running")).when(host).invalidateBot(BOT_ID);
        when(bindings.findAll()).thenReturn(List.of(binding));
        when(inbox.claimNext(anyString(), eq(NOW), any())).thenReturn(Optional.empty());
        when(deliveries.claimNext(anyString(), eq(NOW), any())).thenReturn(Optional.empty());
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        when(scheduler.scheduleWithFixedDelay(task.capture(), eq(0L), eq(1L), eq(TimeUnit.MILLISECONDS)))
                .thenAnswer(ignored -> polling);

        try (PluginRuntimeService runtime = service()) {
            runtime.start();
            assertThatThrownBy(() -> runtime.beforeBotDeletion(BOT_ID))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("callback still running");
            runtime.botDeletionAborted(BOT_ID);
            task.getValue().run();
        }

        verify(host).reconcileBindings(List.of());
    }

    @Test
    void keepsBindingBlockedBetweenSuccessfulMutationPreparationAndItsOutcome() {
        BotPluginBinding binding = binding(true, PluginBindingRuntimeState.ACTIVE);
        when(bindings.findAll()).thenReturn(List.of(binding));
        when(inbox.claimNext(anyString(), eq(NOW), any())).thenReturn(Optional.empty());
        when(deliveries.claimNext(anyString(), eq(NOW), any())).thenReturn(Optional.empty());
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        when(scheduler.scheduleWithFixedDelay(task.capture(), eq(0L), eq(1L), eq(TimeUnit.MILLISECONDS)))
                .thenAnswer(ignored -> polling);

        try (PluginRuntimeService runtime = service()) {
            runtime.start();
            runtime.beforeBindingMutation(BINDING_ID);
            task.getValue().run();
            runtime.bindingMutationCompleted(BINDING_ID);
            task.getValue().run();
        }

        verify(host).quiesceBindingStrict(BINDING_ID);
        ArgumentCaptor<List<BotPluginBinding>> reconciliations = ArgumentCaptor.forClass(List.class);
        verify(host, org.mockito.Mockito.times(2)).reconcileBindings(reconciliations.capture());
        assertThat(reconciliations.getAllValues()).containsExactly(List.of(), List.of(binding));
    }

    @Test
    void bindingQuiescenceFailureRemainsFailClosedAfterMutationAbort() {
        BotPluginBinding binding = binding(true, PluginBindingRuntimeState.ACTIVE);
        doThrow(new IllegalStateException("callback still running"))
                .when(host).quiesceBindingStrict(BINDING_ID);
        when(bindings.findAll()).thenReturn(List.of(binding));
        when(inbox.claimNext(anyString(), eq(NOW), any())).thenReturn(Optional.empty());
        when(deliveries.claimNext(anyString(), eq(NOW), any())).thenReturn(Optional.empty());
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        when(scheduler.scheduleWithFixedDelay(task.capture(), eq(0L), eq(1L), eq(TimeUnit.MILLISECONDS)))
                .thenAnswer(ignored -> polling);

        try (PluginRuntimeService runtime = service()) {
            runtime.start();
            assertThatThrownBy(() -> runtime.beforeBindingMutation(BINDING_ID))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("callback still running");
            runtime.bindingMutationAborted(BINDING_ID);
            task.getValue().run();
        }

        verify(host).reconcileBindings(List.of());
    }

    @Test
    void doesNotMaterializeAClaimedEventWhileBotDeletionIsPrepared() {
        BotPluginBinding binding = binding(true, PluginBindingRuntimeState.ACTIVE);
        InboxEvent event = event();
        when(bindings.findAll()).thenReturn(List.of(binding));
        when(inbox.claimNext(anyString(), eq(NOW), any()))
                .thenReturn(Optional.of(event))
                .thenReturn(Optional.empty());
        when(deliveries.claimNext(anyString(), eq(NOW), any())).thenReturn(Optional.empty());
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        when(scheduler.scheduleWithFixedDelay(task.capture(), eq(0L), eq(1L), eq(TimeUnit.MILLISECONDS)))
                .thenAnswer(ignored -> polling);

        try (PluginRuntimeService runtime = service()) {
            runtime.start();
            runtime.beforeBotDeletion(BOT_ID);
            task.getValue().run();
            runtime.botDeletionCompleted(BOT_ID);
        }

        verify(inbox).markRetry(EVENT_ID, 1L, NOW, NOW.plusMillis(1),
                "Bot deletion is in progress on this instance");
        verify(bindings, never()).findByBotId(BOT_ID);
        verify(host, never()).handlerIds(any(), anyString());
    }

    @Test
    void refusesAClaimedDeliveryWhenTheBotLeaseWasLost() {
        BotPluginBinding binding = binding(true, PluginBindingRuntimeState.ACTIVE);
        PluginDelivery delivery = delivery();
        when(bindings.findAll()).thenReturn(List.of(binding));
        when(botLeases.isOwned(BOT_ID, "instance-a", NOW))
                .thenReturn(true)
                .thenReturn(false);
        when(inbox.claimNextOwned(anyString(), eq("instance-a"), eq(NOW), any()))
                .thenReturn(Optional.empty());
        when(deliveries.claimNextOwned(anyString(), eq("instance-a"), eq(NOW), any()))
                .thenReturn(Optional.of(delivery));
        when(bindings.findById(BINDING_ID)).thenReturn(Optional.of(binding));
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        when(scheduler.scheduleWithFixedDelay(task.capture(), eq(0L), eq(1L), eq(TimeUnit.MILLISECONDS)))
                .thenAnswer(ignored -> polling);

        try (PluginRuntimeService runtime = service(botLeases, "instance-a")) {
            runtime.start();
            task.getValue().run();
        }

        verify(host, never()).executeCancellable(any(), any(), anyString());
        verify(deliveries).markRetry(eq(DELIVERY_ID), anyLong(), eq(NOW), any(), anyString());
    }

    private PluginRuntimeService service() {
        return service(null, null);
    }

    private PluginRuntimeService service(BotLeaseRepository leases, String instanceId) {
        return new PluginRuntimeService(host, inbox, bindings, deliveries, scheduler,
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMillis(1), Duration.ofSeconds(30),
                Duration.ofMillis(1), Duration.ofMillis(1), 3, 1, leases, instanceId);
    }

    private static BotPluginBinding binding(boolean enabled, PluginBindingRuntimeState state) {
        return new BotPluginBinding(BINDING_ID, "example", BOT_ID, enabled, 1L, NOW, NOW,
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
