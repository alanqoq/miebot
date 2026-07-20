package com.mieai.qqbot.plugin.host;

import com.mieai.qqbot.persistence.inbox.EventInboxRepository;
import com.mieai.qqbot.persistence.inbox.InboxEvent;
import com.mieai.qqbot.persistence.plugin.BotPluginBinding;
import com.mieai.qqbot.persistence.plugin.BotPluginBindingRepository;
import com.mieai.qqbot.persistence.plugin.PluginDelivery;
import com.mieai.qqbot.persistence.plugin.PluginDeliveryRepository;
import com.mieai.qqbot.persistence.plugin.PluginBindingRuntimeState;
import com.mieai.qqbot.persistence.lease.BotLeaseRepository;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Durable Inbox-to-plugin pipeline with bounded retries and fenced database transitions. */
public final class PluginRuntimeService implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(PluginRuntimeService.class);
    private final Pf4jPluginHost host;
    private final EventInboxRepository inbox;
    private final BotPluginBindingRepository bindings;
    private final PluginDeliveryRepository deliveries;
    private final ScheduledExecutorService scheduler;
    private final Clock clock;
    private final Duration pollInterval;
    private final Duration leaseDuration;
    private final Duration executionTimeout;
    private final Duration cancellationGrace;
    private final int maxAttempts;
    private final int batchSize;
    private final BotLeaseRepository botLeases;
    private final String instanceId;
    private final String workerId = "plugin-worker-" + UUID.randomUUID();
    private final AtomicBoolean running = new AtomicBoolean();
    private final Object transitionMonitor = new Object();
    private volatile boolean databaseTransitionActive;
    private ScheduledFuture<?> polling;

    public PluginRuntimeService(Pf4jPluginHost host, EventInboxRepository inbox,
            BotPluginBindingRepository bindings, PluginDeliveryRepository deliveries,
            ScheduledExecutorService scheduler, Clock clock, Duration pollInterval,
            Duration leaseDuration, Duration executionTimeout, int maxAttempts, int batchSize) {
        this(host, inbox, bindings, deliveries, scheduler, clock, pollInterval, leaseDuration,
                executionTimeout, Duration.ofSeconds(5), maxAttempts, batchSize, null, null);
    }

    public PluginRuntimeService(Pf4jPluginHost host, EventInboxRepository inbox,
            BotPluginBindingRepository bindings, PluginDeliveryRepository deliveries,
            ScheduledExecutorService scheduler, Clock clock, Duration pollInterval,
            Duration leaseDuration, Duration executionTimeout, Duration cancellationGrace,
            int maxAttempts, int batchSize, BotLeaseRepository botLeases, String instanceId) {
        this.host = Objects.requireNonNull(host, "host must not be null");
        this.inbox = Objects.requireNonNull(inbox, "inbox must not be null");
        this.bindings = Objects.requireNonNull(bindings, "bindings must not be null");
        this.deliveries = Objects.requireNonNull(deliveries, "deliveries must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.pollInterval = positive(pollInterval, "pollInterval");
        this.leaseDuration = positive(leaseDuration, "leaseDuration");
        this.executionTimeout = positive(executionTimeout, "executionTimeout");
        this.cancellationGrace = positive(cancellationGrace, "cancellationGrace");
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be positive");
        if (batchSize < 1 || batchSize > 1000) throw new IllegalArgumentException("batchSize is invalid");
        this.maxAttempts = maxAttempts;
        this.batchSize = batchSize;
        this.botLeases = botLeases;
        this.instanceId = botLeases == null ? null : Objects.requireNonNull(instanceId, "instanceId must not be null");
    }

    public void start() {
        if (!running.compareAndSet(false, true)) return;
        host.start();
        polling = scheduler.scheduleWithFixedDelay(this::tickSafely, 0,
                pollInterval.toMillis(), TimeUnit.MILLISECONDS);
    }

    public boolean isRunning() {
        return running.get();
    }

    public void beforeActiveDatabaseChange() {
        synchronized (transitionMonitor) {
            databaseTransitionActive = true;
            host.invalidateAll();
        }
    }

    public void activeDatabaseChanged() {
        synchronized (transitionMonitor) {
            try {
                host.reload();
            } finally {
                databaseTransitionActive = false;
            }
        }
    }

    public void bindingChanged(UUID bindingId) {
        host.invalidate(bindingId);
        Instant now = clock.instant();
        bindings.findById(bindingId).ifPresent(binding -> {
            if (binding.enabled() && binding.runtimeState().runnable()) {
                deliveries.resumeForBinding(bindingId, now);
            } else {
                deliveries.pauseForBinding(bindingId, now,
                        binding.runtimeError().orElse("Plugin binding is paused"));
            }
        });
    }

    public void resetQuarantinedBinding(UUID bindingId) {
        Objects.requireNonNull(bindingId, "bindingId must not be null");
        BotPluginBinding binding = bindings.findById(bindingId).orElseThrow(
                () -> new IllegalArgumentException("Plugin binding does not exist"));
        if (!binding.enabled()) throw new IllegalStateException("Plugin binding is disabled");
        Instant now = clock.instant();
        bindings.setRuntimeState(bindingId, PluginBindingRuntimeState.ACTIVE, null, now);
        host.invalidate(bindingId);
        deliveries.resumeForBinding(bindingId, now);
    }

    public void reloadPlugins() {
        synchronized (transitionMonitor) {
            host.reload();
        }
    }

    public PluginArtifactInstallResult installArtifact(Path stagedArtifact) {
        synchronized (transitionMonitor) {
            return host.installArtifact(stagedArtifact);
        }
    }

    private void tickSafely() {
        if (!running.get() || databaseTransitionActive) return;
        synchronized (transitionMonitor) {
            if (!running.get() || databaseTransitionActive) return;
            try {
                for (int index = 0; index < batchSize && materializeOne(); index++) {}
                for (int index = 0; index < batchSize && deliverOne(); index++) {}
            } catch (RuntimeException exception) {
                LOGGER.warn("Plugin pipeline poll failed ({})", exception.getClass().getSimpleName());
            }
        }
    }

    private boolean materializeOne() {
        Instant now = clock.instant();
        Optional<InboxEvent> claimed = botLeases == null
                ? inbox.claimNext(workerId, now, leaseDuration)
                : inbox.claimNextOwned(workerId, instanceId, now, leaseDuration);
        if (claimed.isEmpty()) return false;
        InboxEvent event = claimed.get();
        try {
            List<BotPluginBinding> eventBindings = bindings.findByBotId(event.botId()).stream()
                    .filter(BotPluginBinding::enabled)
                    .filter(binding -> binding.runtimeState().runnable())
                    .toList();
            for (BotPluginBinding binding : eventBindings) {
                for (String handlerId : host.handlerIds(binding, event.eventType())) {
                    UUID deliveryId = UUID.nameUUIDFromBytes(
                            (event.id() + ":" + binding.id() + ":" + handlerId)
                                    .getBytes(StandardCharsets.UTF_8));
                    deliveries.createIfAbsent(deliveryId, event.id(), binding.id(), handlerId, now);
                }
            }
            inbox.markDispatched(event.id(), event.fencingToken(), clock.instant());
        } catch (RuntimeException exception) {
            retryOrDeadLetter(event, exception);
        }
        return true;
    }

    private boolean deliverOne() {
        Instant now = clock.instant();
        Optional<PluginDelivery> claimed = botLeases == null
                ? deliveries.claimNext(workerId, now, leaseDuration)
                : deliveries.claimNextOwned(workerId, instanceId, now, leaseDuration);
        if (claimed.isEmpty()) return false;
        PluginDelivery delivery = claimed.get();
        try {
            BotPluginBinding binding = bindings.findById(delivery.bindingId()).orElseThrow(
                    () -> new IllegalStateException("Plugin binding no longer exists"));
            if (botLeases != null && !botLeases.isOwned(binding.botId(), instanceId, clock.instant())) {
                throw new IllegalStateException("Bot lease is no longer owned by this instance");
            }
            InboxEvent event = inbox.findById(delivery.eventId()).orElseThrow(
                    () -> new IllegalStateException("Inbox event no longer exists"));
            if (!binding.enabled() || !binding.runtimeState().runnable()) {
                deliveries.pauseForBinding(binding.id(), clock.instant(),
                        binding.runtimeError().orElse("Plugin binding is paused"));
                return true;
            }
            PluginExecution execution = host.executeCancellable(binding, event, delivery.handlerId());
            try {
                execution.stage().toCompletableFuture()
                        .get(executionTimeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException timeout) {
                execution.cancel();
                if (!execution.await(cancellationGrace)) {
                    quarantine(binding, delivery, timeout);
                    return true;
                }
                throw timeout;
            }
            deliveries.markSucceeded(delivery.id(), delivery.fencingToken(), clock.instant());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            retryDelivery(delivery, exception);
        } catch (ExecutionException | TimeoutException | RuntimeException exception) {
            retryDelivery(delivery, unwrap(exception));
        }
        return true;
    }

    private void quarantine(BotPluginBinding binding, PluginDelivery delivery, Throwable failure) {
        String error = "Plugin execution timed out and did not stop within "
                + cancellationGrace.toMillis() + " ms: " + safeError(failure);
        Instant now = clock.instant();
        host.quarantine(binding.id());
        try {
            bindings.setRuntimeState(binding.id(), PluginBindingRuntimeState.QUARANTINED, error, now);
            deliveries.pauseForBinding(binding.id(), now, error);
        } catch (RuntimeException transitionFailure) {
            LOGGER.error("Could not quarantine plugin binding {} after delivery {} timed out",
                    binding.id(), delivery.id(), transitionFailure);
        }
    }

    private void retryOrDeadLetter(InboxEvent event, Throwable failure) {
        Instant now = clock.instant();
        String error = safeError(failure);
        try {
            if (event.attempt() >= maxAttempts) {
                inbox.markDeadLetter(event.id(), event.fencingToken(), now, error);
            } else {
                inbox.markRetry(event.id(), event.fencingToken(), now,
                        now.plus(backoff(event.attempt())), error);
            }
        } catch (RuntimeException transitionFailure) {
            LOGGER.warn("Could not update Inbox event {} after plugin materialization failure ({})",
                    event.id(), transitionFailure.getClass().getSimpleName());
        }
    }

    private void retryDelivery(PluginDelivery delivery, Throwable failure) {
        Instant now = clock.instant();
        String error = safeError(failure);
        try {
            if (delivery.attempt() >= maxAttempts) {
                deliveries.markDeadLetter(delivery.id(), delivery.fencingToken(), now, error);
            } else {
                deliveries.markRetry(delivery.id(), delivery.fencingToken(), now,
                        now.plus(backoff(delivery.attempt())), error);
            }
        } catch (RuntimeException transitionFailure) {
            LOGGER.warn("Could not update plugin delivery {} after failure ({})",
                    delivery.id(), transitionFailure.getClass().getSimpleName());
        }
    }

    private static Duration backoff(int attempt) {
        long seconds = Math.min(300L, 1L << Math.min(Math.max(attempt - 1, 0), 8));
        return Duration.ofSeconds(seconds);
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) current = current.getCause();
        return current;
    }

    private static String safeError(Throwable failure) {
        Throwable current = unwrap(failure);
        String message = current.getMessage();
        String value = current.getClass().getSimpleName();
        if (message != null && !message.isBlank()) value += ": " + message.replace('\n', ' ').replace('\r', ' ');
        return value.length() > 512 ? value.substring(0, 512) : value;
    }

    private static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isZero() || value.isNegative()) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) return;
        ScheduledFuture<?> task = polling;
        if (task != null) task.cancel(false);
        synchronized (transitionMonitor) {
            host.close();
        }
    }
}
