package com.mieai.qqbot.runtime.supervisor;

import com.mieai.qqbot.domain.bot.BotDefinition;
import com.mieai.qqbot.domain.bot.BotEnvironment;
import com.mieai.qqbot.domain.bot.BotId;
import com.mieai.qqbot.domain.bot.BotRevision;
import com.mieai.qqbot.domain.bot.GatewayIntents;
import com.mieai.qqbot.domain.bot.QqAppId;
import com.mieai.qqbot.domain.bot.ShardSpec;
import com.mieai.qqbot.persistence.bot.BotRepository;
import com.mieai.qqbot.persistence.bot.SecretCiphertext;
import com.mieai.qqbot.persistence.bot.StoredBot;
import com.mieai.qqbot.persistence.inbox.EventInboxRepository;
import com.mieai.qqbot.persistence.inbox.IncomingEvent;
import com.mieai.qqbot.persistence.lease.BotLease;
import com.mieai.qqbot.persistence.lease.BotLeaseRepository;
import com.mieai.qqbot.gateway.GatewayDispatch;
import com.mieai.qqbot.runtime.configuration.BotConfigurationChange;
import com.mieai.qqbot.runtime.configuration.BotConfigurationChangeListener;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Reconciles persisted bot desired state into isolated, generation-fenced runtimes. */
public final class BotSupervisor implements BotConfigurationChangeListener, AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(BotSupervisor.class);
    private static final Duration DEFAULT_RECONCILIATION_INTERVAL = Duration.ofSeconds(30);
    private static final Duration DEFAULT_SHUTDOWN_TIMEOUT = Duration.ofSeconds(10);
    private static final BotRuntimeFailure START_FAILURE = new BotRuntimeFailure(
            "RUNTIME_START_FAILED", "The bot runtime could not complete its initial start", true);
    private static final BotRuntimeFailure FACTORY_FAILURE = new BotRuntimeFailure(
            "RUNTIME_CONFIGURATION_FAILED", "The bot runtime could not be created", false);
    private static final BotRuntimeFailure INBOX_PERSISTENCE_FAILURE = new BotRuntimeFailure(
            "INBOX_PERSISTENCE_FAILED",
            "The latest QQ Gateway event could not be persisted; the session will retry",
            true);
    private static final BotRuntimeFailure LEASE_NOT_ACQUIRED = new BotRuntimeFailure(
            "LEASE_NOT_ACQUIRED",
            "Another application instance currently owns this bot shard",
            true);

    private final BotRepository repository;
    private final BotRuntimeFactory runtimeFactory;
    /** Null only for legacy/unit-test construction without an Inbox integration. */
    private final EventInboxRepository inboxRepository;
    /** Null only for legacy/unit-test construction; production uses SQL fencing leases. */
    private final BotLeaseRepository leaseRepository;
    private final String leaseOwnerId;
    private final Duration leaseDuration;
    private final ConcurrentMap<BotId, BotLease> leases = new ConcurrentHashMap<>();
    private final Duration reconciliationInterval;
    private final Duration shutdownTimeout;
    private final Clock clock;
    private final ScheduledThreadPoolExecutor reconciler;
    private final ConcurrentMap<BotId, Slot> slots = new ConcurrentHashMap<>();
    private final AtomicLong databaseEpoch = new AtomicLong();
    private final AtomicLong configurationSignal = new AtomicLong();
    private final AtomicReference<Lifecycle> lifecycle = new AtomicReference<>(Lifecycle.NEW);
    private final AtomicReference<CompletableFuture<Void>> shutdown = new AtomicReference<>();
    private final Object topologyMonitor = new Object();
    /** Closed from the pre-swap fence until the database coordinator reports commit or rollback. */
    private volatile boolean databaseTransitionActive;

    private volatile ScheduledFuture<?> periodicReconciliation;

    public BotSupervisor(BotRepository repository, BotRuntimeFactory runtimeFactory) {
        this(
                repository,
                runtimeFactory,
                DEFAULT_RECONCILIATION_INTERVAL,
                DEFAULT_SHUTDOWN_TIMEOUT,
                Clock.systemUTC(),
                null,
                null,
                null,
                null);
    }

    public BotSupervisor(
            BotRepository repository,
            BotRuntimeFactory runtimeFactory,
            Duration reconciliationInterval,
            Duration shutdownTimeout,
            Clock clock) {
        this(
                repository,
                runtimeFactory,
                reconciliationInterval,
                shutdownTimeout,
                clock,
                null,
                null,
                null,
                null);
    }

    /** Production constructor with the durable Gateway Inbox integration. */
    public BotSupervisor(
            BotRepository repository,
            BotRuntimeFactory runtimeFactory,
            Duration reconciliationInterval,
            Duration shutdownTimeout,
            Clock clock,
            EventInboxRepository inboxRepository) {
        this(repository, runtimeFactory, reconciliationInterval, shutdownTimeout, clock,
                inboxRepository, null, null, null);
    }

    /** Production constructor with durable Inbox and SQL bot lease fencing. */
    public BotSupervisor(
            BotRepository repository,
            BotRuntimeFactory runtimeFactory,
            Duration reconciliationInterval,
            Duration shutdownTimeout,
            Clock clock,
            EventInboxRepository inboxRepository,
            BotLeaseRepository leaseRepository,
            String leaseOwnerId,
            Duration leaseDuration) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.runtimeFactory =
                Objects.requireNonNull(runtimeFactory, "runtimeFactory must not be null");
        this.inboxRepository = inboxRepository;
        this.leaseRepository = leaseRepository;
        this.leaseOwnerId = leaseRepository == null ? null : requireLeaseOwner(leaseOwnerId);
        this.leaseDuration = leaseRepository == null ? null : requirePositive(leaseDuration, "leaseDuration");
        this.reconciliationInterval =
                requirePositive(reconciliationInterval, "reconciliationInterval");
        if (leaseRepository != null && this.leaseDuration.compareTo(this.reconciliationInterval) <= 0) {
            throw new IllegalArgumentException("leaseDuration must exceed reconciliationInterval");
        }
        this.shutdownTimeout = requirePositive(shutdownTimeout, "shutdownTimeout");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        reconciler = new ScheduledThreadPoolExecutor(1, daemonThreadFactory());
        reconciler.setRemoveOnCancelPolicy(true);
        reconciler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        reconciler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
    }

    /** Loads current desired state and starts all enabled bots. */
    public CompletionStage<Void> start() {
        if (lifecycle.compareAndSet(Lifecycle.NEW, Lifecycle.RUNNING)) {
            CompletionStage<Void> initial = submit(this::reconcileAllInternal);
            periodicReconciliation = reconciler.scheduleWithFixedDelay(
                    this::periodicReconcile,
                    reconciliationInterval.toNanos(),
                    reconciliationInterval.toNanos(),
                    TimeUnit.NANOSECONDS);
            return initial;
        }
        if (lifecycle.get() == Lifecycle.RUNNING) {
            return barrier();
        }
        return CompletableFuture.failedFuture(
                new IllegalStateException("Bot supervisor has already been stopped"));
    }

    /** Re-reads and reconciles one bot on the supervisor's single reconciliation thread. */
    public CompletionStage<Void> reconcile(BotId botId) {
        Objects.requireNonNull(botId, "botId must not be null");
        return submit(() -> reconcileOneSafely(botId, false));
    }

    /** Re-reads all configured bots and stops runtimes absent from the active database. */
    public CompletionStage<Void> reconcileAll() {
        return submit(this::reconcileAllInternal);
    }

    /** Forces a fresh runtime for the latest enabled revision. */
    public CompletionStage<Void> restart(BotId botId) {
        Objects.requireNonNull(botId, "botId must not be null");
        return submit(() -> reconcileOneSafely(botId, true));
    }

    /**
     * Fences every old-database runtime synchronously, then reconciles from the new active database.
     */
    public CompletionStage<Void> activeDatabaseChanged() {
        List<ManagedBotRuntime> retired;
        synchronized (topologyMonitor) {
            // Keep the gate closed while retiring anything that could have been created by a
            // legacy caller between the pre-fence callback and the delegate swap.
            retired = isolateAllForNewDatabaseLocked();
            databaseTransitionActive = false;
        }
        retired.forEach(runtime -> stopBestEffort(runtime, null));
        if (lifecycle.get() != Lifecycle.RUNNING) {
            return CompletableFuture.completedFuture(null);
        }
        return submit(this::reconcileAllInternal);
    }

    /**
     * Synchronously fences the current runtime topology before a DataSource delegate is swapped.
     * The database coordinator must invoke this while the old delegate is still active; the
     * matching {@link #activeDatabaseChanged()} call performs reconciliation after activation.
     */
    public void beforeActiveDatabaseChange() {
        List<ManagedBotRuntime> retired;
        synchronized (topologyMonitor) {
            if (lifecycle.get() != Lifecycle.RUNNING) {
                return;
            }
            if (databaseTransitionActive) {
                throw new IllegalStateException("A database transition is already in progress");
            }
            databaseTransitionActive = true;
            try {
                // Set the gate before detaching. Dispatch callbacks that arrive after this point
                // must be rejected rather than acknowledged against an old slot.
                retired = isolateAllForNewDatabaseLocked();
            } catch (RuntimeException | Error failure) {
                databaseTransitionActive = false;
                throw failure;
            }
        }
        retired.forEach(runtime -> stopBestEffort(runtime, null));
    }

    /** Synchronous configuration notification used by {@code BotConfigurationService}. */
    @Override
    public void onCommitted(BotConfigurationChange change) {
        Objects.requireNonNull(change, "change must not be null");
        if (lifecycle.get() != Lifecycle.RUNNING) {
            return;
        }
        try {
            ManagedBotRuntime disabled = null;
            synchronized (topologyMonitor) {
                if (databaseTransitionActive) {
                    // The post-swap reconciliation reads the committed configuration from the
                    // active delegate. Do not mutate an old-database slot during the transition.
                    return;
                }
                configurationSignal.incrementAndGet();
                Slot slot = slots.get(change.botId());
                if (slot != null) {
                    disabled = slot.noteCommitted(
                            change, databaseEpoch.get(), clock.instant());
                }
            }
            if (disabled != null) {
                stopBestEffort(disabled, change.botId());
            }
            if (!change.enabled()) releaseLease(change.botId());
            submitWithoutResult(() -> reconcileOneSafely(change.botId(), false));
        } catch (RuntimeException exception) {
            LOGGER.warn(
                    "Unable to enqueue committed bot configuration {} at revision {} ({})",
                    change.botId(),
                    change.revision().value(),
                    exception.getClass().getSimpleName());
        }
    }

    public Optional<BotRuntimeStatus> status(BotId botId) {
        Objects.requireNonNull(botId, "botId must not be null");
        Slot slot = slots.get(botId);
        return slot == null ? Optional.empty() : Optional.of(slot.snapshot());
    }

    public List<BotRuntimeStatus> statuses() {
        return slots.values().stream()
                .map(Slot::snapshot)
                .sorted(Comparator.comparing(status -> status.botId().toString()))
                .toList();
    }

    public BotRuntimeSummary summary() {
        return BotRuntimeSummary.from(statuses(), clock.instant());
    }

    /** Starts shutdown once and completes after all installed runtimes have been stopped or timed out. */
    public CompletionStage<Void> shutdown() {
        CompletableFuture<Void> existing = shutdown.get();
        if (existing != null) {
            return existing.minimalCompletionStage();
        }

        CompletableFuture<Void> result = new CompletableFuture<>();
        if (!shutdown.compareAndSet(null, result)) {
            return shutdown.get().minimalCompletionStage();
        }

        lifecycle.getAndUpdate(current ->
                current == Lifecycle.CLOSED ? Lifecycle.CLOSED : Lifecycle.CLOSING);
        ScheduledFuture<?> periodic = periodicReconciliation;
        if (periodic != null) {
            periodic.cancel(false);
        }

        List<ManagedBotRuntime> retired = isolateAllForShutdown();
        List<CompletableFuture<Void>> stopping = retired.stream()
                .map(runtime -> stopForShutdown(runtime).toCompletableFuture())
                .toList();
        reconciler.shutdown();

        CompletableFuture<Void> allStopped = CompletableFuture.allOf(
                stopping.toArray(CompletableFuture[]::new));
        allStopped
                .orTimeout(shutdownTimeout.toNanos(), TimeUnit.NANOSECONDS)
                .handle((ignored, failure) -> null)
                .whenComplete((ignored, failure) -> {
                    lifecycle.set(Lifecycle.CLOSED);
                    reconciler.shutdownNow();
                    result.complete(null);
                });
        return result.minimalCompletionStage();
    }

    @Override
    public void close() {
        try {
            shutdown().toCompletableFuture().get(
                    shutdownTimeout.plusSeconds(1L).toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            reconciler.shutdownNow();
        } catch (ExecutionException | TimeoutException exception) {
            reconciler.shutdownNow();
        }
    }

    private void periodicReconcile() {
        if (lifecycle.get() != Lifecycle.RUNNING) {
            return;
        }
        try {
            reconcileAllInternal();
        } catch (RuntimeException exception) {
            LOGGER.warn(
                    "Periodic bot reconciliation failed ({})",
                    exception.getClass().getSimpleName());
        }
    }

    private void reconcileAllInternal() {
        if (lifecycle.get() != Lifecycle.RUNNING || databaseTransitionActive) {
            return;
        }
        List<StoredBot> configured = List.copyOf(repository.findAll());
        Set<BotId> botIds = new LinkedHashSet<>();
        configured.forEach(bot -> botIds.add(bot.id()));
        botIds.addAll(slots.keySet());
        botIds.forEach(botId -> reconcileOneSafely(botId, false));
    }

    private void reconcileOneSafely(BotId botId, boolean forceRestart) {
        try {
            reconcileOneInternal(botId, forceRestart);
        } catch (RuntimeException exception) {
            Slot slot = slots.get(botId);
            if (slot != null) {
                slot.recordFailure(
                        slot.currentGeneration(),
                        slot.databaseEpoch(),
                        new BotRuntimeFailure(
                                "CONFIGURATION_READ_FAILED",
                                "The latest bot configuration could not be read",
                                true),
                        false,
                        clock.instant());
            }
            LOGGER.warn(
                    "Bot reconciliation failed for {} ({})",
                    botId,
                    exception.getClass().getSimpleName());
        }
    }

    private void reconcileOneInternal(BotId botId, boolean forceRestart) {
        if (lifecycle.get() != Lifecycle.RUNNING || databaseTransitionActive) {
            return;
        }
        long epoch = databaseEpoch.get();
        long signal = configurationSignal.get();
        Optional<StoredBot> loaded = repository.findById(botId);
        if (databaseEpoch.get() != epoch || lifecycle.get() != Lifecycle.RUNNING) {
            return;
        }
        if (configurationSignal.get() != signal) {
            retryReconciliation(botId);
            return;
        }
        if (loaded.isEmpty()) {
            releaseLease(botId);
            if (!removeMissing(botId, epoch, signal)) {
                retryReconciliation(botId);
            }
            return;
        }

        StoredBot stored = loaded.orElseThrow();
        Slot slot;
        boolean retry;
        synchronized (topologyMonitor) {
            if (databaseTransitionActive
                    || databaseEpoch.get() != epoch
                    || lifecycle.get() != Lifecycle.RUNNING) {
                return;
            }
            retry = configurationSignal.get() != signal;
            if (retry) {
                slot = null;
            } else {
                slot = slots.computeIfAbsent(
                        botId,
                        ignored -> Slot.from(stored, epoch, clock.instant()));
            }
        }
        if (retry) {
            retryReconciliation(botId);
            return;
        }

        if (databaseTransitionActive || databaseEpoch.get() != epoch) {
            return;
        }

        if (stored.definition().enabled()) {
            if (!ensureLease(stored)) {
                ManagedBotRuntime retired = slot.leaseUnavailable(stored, clock.instant());
                if (retired != null) stopBestEffort(retired, botId);
                return;
            }
        } else {
            releaseLease(botId);
        }

        ReconcileDecision decision = slot.decide(stored, epoch, forceRestart, clock.instant());
        if (decision.stale()) {
            return;
        }
        if (decision.retired() != null) {
            stopBestEffort(decision.retired(), botId);
        }
        if (!decision.start()) {
            return;
        }

        SlotObserver observer = new SlotObserver(slot, decision.generation(), epoch);
        ManagedBotRuntime created;
        try {
            created = Objects.requireNonNull(
                    runtimeFactory.create(stored, observer), "runtimeFactory returned null");
        } catch (RuntimeException exception) {
            slot.creationFailed(
                    decision.generation(), epoch, FACTORY_FAILURE, clock.instant());
            return;
        }

        boolean installed;
        synchronized (topologyMonitor) {
            installed = lifecycle.get() == Lifecycle.RUNNING
                    && !databaseTransitionActive
                    && databaseEpoch.get() == epoch
                    && slots.get(botId) == slot
                    && slot.install(decision.generation(), epoch, created);
        }
        if (!installed) {
            stopBestEffort(created, botId);
            return;
        }

        CompletionStage<Void> starting;
        try {
            starting = Objects.requireNonNull(created.start(), "managed runtime start returned null");
        } catch (RuntimeException exception) {
            handleStartFailure(slot, decision.generation(), epoch);
            return;
        }
        starting.whenComplete((ignored, failure) -> {
            if (failure != null) {
                dispatchObserver(() -> handleStartFailure(slot, decision.generation(), epoch));
            }
        });
    }

    private void handleStartFailure(Slot slot, long generation, long epoch) {
        if (!isCurrent(slot, generation, epoch)) {
            return;
        }
        // A GatewaySession may already be in BACKING_OFF here. Only STARTING becomes FAILED;
        // the runtime remains installed so its internal reconnect can later report ONLINE.
        slot.recordStartFailureIfAbsent(generation, epoch, START_FAILURE, clock.instant());
    }

    private boolean removeMissing(BotId botId, long epoch, long signal) {
        Slot removed;
        synchronized (topologyMonitor) {
            if (databaseTransitionActive
                    || databaseEpoch.get() != epoch
                    || configurationSignal.get() != signal) {
                return false;
            }
            removed = slots.remove(botId);
        }
        if (removed != null) {
            releaseLease(botId);
            ManagedBotRuntime runtime = removed.detach(clock.instant(), BotRuntimeState.STOPPED);
            if (runtime != null) {
                stopBestEffort(runtime, botId);
            }
        }
        return true;
    }

    private void retryReconciliation(BotId botId) {
        submitWithoutResult(() -> reconcileOneSafely(botId, false));
    }

    private List<ManagedBotRuntime> isolateAllForNewDatabase() {
        synchronized (topologyMonitor) {
            return isolateAllForNewDatabaseLocked();
        }
    }

    private List<ManagedBotRuntime> isolateAllForNewDatabaseLocked() {
        List<ManagedBotRuntime> retired = new ArrayList<>();
        databaseEpoch.incrementAndGet();
        configurationSignal.incrementAndGet();
        Instant now = clock.instant();
        slots.values().forEach(slot -> {
            ManagedBotRuntime runtime = slot.detach(now, BotRuntimeState.STOPPED);
            if (runtime != null) {
                retired.add(runtime);
            }
        });
        slots.clear();
        releaseAllLeases();
        return retired;
    }

    private List<ManagedBotRuntime> isolateAllForShutdown() {
        List<ManagedBotRuntime> retired = new ArrayList<>();
        synchronized (topologyMonitor) {
            databaseTransitionActive = false;
            databaseEpoch.incrementAndGet();
            configurationSignal.incrementAndGet();
            Instant now = clock.instant();
            slots.values().forEach(slot -> {
                ManagedBotRuntime runtime = slot.detach(now, BotRuntimeState.STOPPING);
                if (runtime != null) {
                    retired.add(runtime);
                }
            });
            slots.clear();
            releaseAllLeases();
        }
        return retired;
    }

    private CompletionStage<Void> stopForShutdown(ManagedBotRuntime runtime) {
        try {
            CompletionStage<Void> stopping =
                    Objects.requireNonNull(runtime.stop(), "managed runtime stop returned null");
            return stopping.handle((ignored, failure) -> null);
        } catch (RuntimeException exception) {
            return CompletableFuture.completedFuture(null);
        }
    }

    private void stopBestEffort(ManagedBotRuntime runtime, BotId botId) {
        try {
            CompletionStage<Void> stopping =
                    Objects.requireNonNull(runtime.stop(), "managed runtime stop returned null");
            stopping.whenComplete((ignored, failure) -> {
                if (failure != null && botId != null) {
                    LOGGER.warn("Bot runtime cleanup failed for {}", botId);
                }
            });
        } catch (RuntimeException exception) {
            if (botId != null) {
                LOGGER.warn(
                        "Bot runtime cleanup could not start for {} ({})",
                        botId,
                        exception.getClass().getSimpleName());
            }
        }
    }

    private boolean isCurrent(Slot slot, long generation, long epoch) {
        return lifecycle.get() == Lifecycle.RUNNING
                && databaseEpoch.get() == epoch
                && slots.get(slot.botId()) == slot
                && slot.accepts(generation, epoch);
    }

    private boolean ensureLease(StoredBot stored) {
        if (leaseRepository == null) return true;
        BotId botId = stored.id();
        Instant now = clock.instant();
        BotLease current = leases.get(botId);
        try {
            int desiredShard = stored.definition().shardSpec().index();
            if (current != null && current.shardIndex() != desiredShard) {
                releaseLease(botId);
                current = null;
            }
            if (current != null && leaseRepository.renew(current, now, leaseDuration)) {
                return true;
            }
            Optional<BotLease> acquired = leaseRepository.acquire(
                    botId, desiredShard, leaseOwnerId, now, leaseDuration);
            if (acquired.isPresent()) {
                leases.put(botId, acquired.orElseThrow());
                return true;
            }
            leases.remove(botId);
            return false;
        } catch (RuntimeException exception) {
            LOGGER.warn("Unable to acquire or renew bot lease for {} ({})",
                    botId, exception.getClass().getSimpleName());
            leases.remove(botId);
            return false;
        }
    }

    private void releaseLease(BotId botId) {
        if (leaseRepository == null) return;
        BotLease lease = leases.remove(botId);
        if (lease != null) {
            try {
                leaseRepository.release(lease);
            } catch (RuntimeException exception) {
                LOGGER.debug("Unable to release bot lease for {}", botId, exception);
            }
        }
    }

    private void releaseAllLeases() {
        if (leaseRepository == null) return;
        List<BotId> ids = new ArrayList<>(leases.keySet());
        ids.forEach(this::releaseLease);
    }

    private static String requireLeaseOwner(String value) {
        if (value == null || value.isBlank() || value.length() > 255
                || value.codePoints().anyMatch(Character::isWhitespace)
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("leaseOwnerId is invalid");
        }
        return value;
    }

    private CompletionStage<Void> submit(Runnable task) {
        Objects.requireNonNull(task, "task must not be null");
        CompletableFuture<Void> result = new CompletableFuture<>();
        if (lifecycle.get() != Lifecycle.RUNNING) {
            result.complete(null);
            return result.minimalCompletionStage();
        }
        try {
            reconciler.execute(() -> {
                if (lifecycle.get() != Lifecycle.RUNNING) {
                    result.complete(null);
                    return;
                }
                try {
                    task.run();
                    result.complete(null);
                } catch (RuntimeException exception) {
                    result.completeExceptionally(exception);
                }
            });
        } catch (RejectedExecutionException exception) {
            result.complete(null);
        }
        return result.minimalCompletionStage();
    }

    private CompletionStage<Void> barrier() {
        return submit(() -> {});
    }

    private void submitWithoutResult(Runnable task) {
        submit(task).exceptionally(failure -> null);
    }

    private void dispatchObserver(Runnable task) {
        if (lifecycle.get() != Lifecycle.RUNNING) {
            return;
        }
        submitWithoutResult(task);
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        value.toNanos();
        return value;
    }

    private static ThreadFactory daemonThreadFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "qqbot-supervisor");
            thread.setDaemon(true);
            return thread;
        };
    }

    private enum Lifecycle {
        NEW,
        RUNNING,
        CLOSING,
        CLOSED
    }

    private record RuntimeFingerprint(
            QqAppId appId,
            BotEnvironment environment,
            GatewayIntents intents,
            ShardSpec shardSpec,
            SecretCiphertext appSecret) {

        private static RuntimeFingerprint from(StoredBot stored) {
            BotDefinition definition = stored.definition();
            return new RuntimeFingerprint(
                    definition.appId(),
                    definition.environment(),
                    definition.intents(),
                    definition.shardSpec(),
                    stored.appSecret());
        }
    }

    private record ReconcileDecision(
            boolean stale,
            boolean start,
            long generation,
            ManagedBotRuntime retired) {

        private static ReconcileDecision staleDecision() {
            return new ReconcileDecision(true, false, -1L, null);
        }

        private static ReconcileDecision retained() {
            return new ReconcileDecision(false, false, -1L, null);
        }

        private static ReconcileDecision stopped(ManagedBotRuntime retired) {
            return new ReconcileDecision(false, false, -1L, retired);
        }

        private static ReconcileDecision starting(long generation, ManagedBotRuntime retired) {
            return new ReconcileDecision(false, true, generation, retired);
        }
    }

    private static final class Slot {
        private final BotId botId;
        private final long epoch;
        private BotEnvironment environment;

        private BotRevision announcedRevision;
        private BotRevision configurationRevision;
        private boolean desiredEnabled;
        private long generation;
        private ManagedBotRuntime runtime;
        private RuntimeFingerprint runtimeFingerprint;
        private BotRevision attemptedRevision;
        private RuntimeFingerprint attemptedFingerprint;

        private BotRuntimeState state;
        private Instant stateChangedAt;
        private Instant startedAt;
        private Instant readyAt;
        private Instant lastHeartbeatAt;
        private Instant lastDispatchAt;
        private BotSessionSnapshot session;
        private long reconnectCount;
        private BotRuntimeFailure lastFailure;
        private Instant lastFailureAt;

        private Slot(StoredBot stored, long epoch, Instant now) {
            botId = stored.id();
            this.epoch = epoch;
            environment = stored.definition().environment();
            configurationRevision = stored.definition().revision();
            announcedRevision = configurationRevision;
            desiredEnabled = stored.definition().enabled();
            state = desiredEnabled ? BotRuntimeState.STOPPED : BotRuntimeState.DISABLED;
            stateChangedAt = now;
        }

        static Slot from(StoredBot stored, long epoch, Instant now) {
            return new Slot(stored, epoch, now);
        }

        synchronized BotId botId() {
            return botId;
        }

        synchronized BotEnvironment environment() {
            return environment;
        }

        synchronized String fallbackPlatformEventId(
                long expectedGeneration, String dispatchSessionId, long sequence) {
            String sessionId = dispatchSessionId;
            if (sessionId == null || sessionId.isBlank()) {
                sessionId = session == null ? "generation-" + expectedGeneration : session.sessionId();
            }
            return "gateway:" + sessionId + ":" + sequence;
        }

        synchronized long databaseEpoch() {
            return epoch;
        }

        synchronized long currentGeneration() {
            return generation;
        }

        synchronized ManagedBotRuntime noteCommitted(
                BotConfigurationChange change, long currentEpoch, Instant now) {
            if (epoch != currentEpoch
                    || (announcedRevision != null
                            && change.revision().compareTo(announcedRevision) < 0)) {
                return null;
            }
            announcedRevision = change.revision();
            if (change.enabled()) {
                return null;
            }

            configurationRevision = change.revision();
            desiredEnabled = false;
            attemptedRevision = change.revision();
            attemptedFingerprint = null;
            generation++;
            ManagedBotRuntime detached = runtime;
            runtime = null;
            runtimeFingerprint = null;
            setState(BotRuntimeState.DISABLED, now);
            return detached;
        }

        synchronized ReconcileDecision decide(
                StoredBot stored, long currentEpoch, boolean forceRestart, Instant now) {
            BotDefinition definition = stored.definition();
            if (epoch != currentEpoch
                    || definition.revision().compareTo(announcedRevision) < 0) {
                return ReconcileDecision.staleDecision();
            }
            announcedRevision = definition.revision();
            configurationRevision = definition.revision();
            desiredEnabled = definition.enabled();
            environment = definition.environment();
            RuntimeFingerprint desiredFingerprint = RuntimeFingerprint.from(stored);

            if (!desiredEnabled) {
                attemptedRevision = configurationRevision;
                attemptedFingerprint = desiredFingerprint;
                generation++;
                ManagedBotRuntime detached = runtime;
                runtime = null;
                runtimeFingerprint = null;
                setState(BotRuntimeState.DISABLED, now);
                return ReconcileDecision.stopped(detached);
            }

            if (!forceRestart
                    && runtime != null
                    && desiredFingerprint.equals(runtimeFingerprint)) {
                return ReconcileDecision.retained();
            }
            if (!forceRestart
                    && runtime == null
                    && state == BotRuntimeState.FAILED
                    && configurationRevision.equals(attemptedRevision)
                    && desiredFingerprint.equals(attemptedFingerprint)) {
                return ReconcileDecision.retained();
            }

            generation++;
            ManagedBotRuntime detached = runtime;
            runtime = null;
            runtimeFingerprint = null;
            attemptedRevision = configurationRevision;
            attemptedFingerprint = desiredFingerprint;
            startedAt = now;
            readyAt = null;
            lastHeartbeatAt = null;
            lastDispatchAt = null;
            session = null;
            reconnectCount = 0L;
            lastFailure = null;
            lastFailureAt = null;
            setState(BotRuntimeState.STARTING, now);
            return ReconcileDecision.starting(generation, detached);
        }

        synchronized boolean install(
                long expectedGeneration, long currentEpoch, ManagedBotRuntime created) {
            if (!accepts(expectedGeneration, currentEpoch)
                    || !desiredEnabled
                    || runtime != null
                    || !configurationRevision.equals(announcedRevision)) {
                return false;
            }
            runtime = created;
            runtimeFingerprint = attemptedFingerprint;
            return true;
        }

        synchronized boolean accepts(long expectedGeneration, long currentEpoch) {
            return epoch == currentEpoch && generation == expectedGeneration;
        }

        synchronized ManagedBotRuntime detach(Instant now, BotRuntimeState detachedState) {
            generation++;
            ManagedBotRuntime detached = runtime;
            runtime = null;
            runtimeFingerprint = null;
            desiredEnabled = false;
            setState(detachedState, now);
            return detached;
        }

        synchronized ManagedBotRuntime leaseUnavailable(StoredBot stored, Instant now) {
            BotDefinition definition = stored.definition();
            announcedRevision = definition.revision();
            configurationRevision = definition.revision();
            desiredEnabled = true;
            environment = definition.environment();
            generation++;
            ManagedBotRuntime detached = runtime;
            runtime = null;
            runtimeFingerprint = null;
            attemptedRevision = null;
            attemptedFingerprint = null;
            lastFailure = LEASE_NOT_ACQUIRED;
            lastFailureAt = now;
            setState(BotRuntimeState.STOPPED, now);
            return detached;
        }

        synchronized void stateChanged(
                long expectedGeneration,
                long currentEpoch,
                BotRuntimeState next,
                Instant now) {
            if (!accepts(expectedGeneration, currentEpoch) || !desiredEnabled) {
                return;
            }
            setState(next, now);
            if (next == BotRuntimeState.ONLINE && readyAt == null) {
                readyAt = now;
            }
        }

        synchronized void ready(
                long expectedGeneration,
                long currentEpoch,
                BotSessionSnapshot snapshot,
                Instant now) {
            if (!accepts(expectedGeneration, currentEpoch) || !desiredEnabled) {
                return;
            }
            session = snapshot;
            readyAt = now;
            setState(BotRuntimeState.ONLINE, now);
        }

        synchronized void heartbeat(
                long expectedGeneration, long currentEpoch, Long sequence, Instant now) {
            if (accepts(expectedGeneration, currentEpoch) && desiredEnabled) {
                lastHeartbeatAt = now;
                updateSequence(sequence);
            }
        }

        synchronized void dispatch(
                long expectedGeneration, long currentEpoch, long sequence, Instant now) {
            if (accepts(expectedGeneration, currentEpoch) && desiredEnabled) {
                lastDispatchAt = now;
                updateSequence(sequence);
            }
        }

        synchronized void reconnect(long expectedGeneration, long currentEpoch, Instant now) {
            if (!accepts(expectedGeneration, currentEpoch) || !desiredEnabled) {
                return;
            }
            reconnectCount = Math.incrementExact(reconnectCount);
            setState(BotRuntimeState.RECONNECTING, now);
        }

        synchronized void recordFailure(
                long expectedGeneration,
                long currentEpoch,
                BotRuntimeFailure failure,
                boolean terminal,
                Instant now) {
            if (!accepts(expectedGeneration, currentEpoch)) {
                return;
            }
            lastFailure = failure;
            lastFailureAt = now;
            if (terminal || state == BotRuntimeState.STARTING) {
                setState(BotRuntimeState.FAILED, now);
            }
        }

        synchronized void creationFailed(
                long expectedGeneration,
                long currentEpoch,
                BotRuntimeFailure failure,
                Instant now) {
            if (!accepts(expectedGeneration, currentEpoch)) {
                return;
            }
            lastFailure = failure;
            lastFailureAt = now;
            setState(BotRuntimeState.FAILED, now);
            generation++;
        }

        synchronized void recordStartFailureIfAbsent(
                long expectedGeneration,
                long currentEpoch,
                BotRuntimeFailure failure,
                Instant now) {
            if (!accepts(expectedGeneration, currentEpoch)
                    || state != BotRuntimeState.STARTING
                    || lastFailure != null) {
                return;
            }
            lastFailure = failure;
            lastFailureAt = now;
            setState(BotRuntimeState.FAILED, now);
        }

        synchronized BotRuntimeStatus snapshot() {
            return new BotRuntimeStatus(
                    botId,
                    configurationRevision,
                    desiredEnabled,
                    state,
                    stateChangedAt,
                    Optional.ofNullable(startedAt),
                    Optional.ofNullable(readyAt),
                    Optional.ofNullable(lastHeartbeatAt),
                    Optional.ofNullable(lastDispatchAt),
                    Optional.ofNullable(session),
                    reconnectCount,
                    Optional.ofNullable(lastFailure),
                    Optional.ofNullable(lastFailureAt));
        }

        private void setState(BotRuntimeState next, Instant now) {
            if (state != next) {
                state = next;
                stateChangedAt = now;
            }
        }

        private void updateSequence(Long nextSequence) {
            if (session != null && nextSequence != null && nextSequence >= session.sequence()) {
                session = session.withSequence(nextSequence);
            }
        }
    }

    private final class SlotObserver implements BotRuntimeObserver {
        private final Slot slot;
        private final long generation;
        private final long epoch;

        private SlotObserver(Slot slot, long generation, long epoch) {
            this.slot = slot;
            this.generation = generation;
            this.epoch = epoch;
        }

        @Override
        public void onStateChanged(BotRuntimeState state) {
            Objects.requireNonNull(state, "state must not be null");
            dispatchObserver(() -> {
                if (isCurrent(slot, generation, epoch)) {
                    slot.stateChanged(generation, epoch, state, clock.instant());
                }
            });
        }

        @Override
        public void onReady(BotSessionSnapshot snapshot) {
            Objects.requireNonNull(snapshot, "snapshot must not be null");
            dispatchObserver(() -> {
                if (isCurrent(slot, generation, epoch)) {
                    slot.ready(generation, epoch, snapshot, clock.instant());
                }
            });
        }

        @Override
        public void onHeartbeatAcknowledged() {
            dispatchObserver(() -> {
                if (isCurrent(slot, generation, epoch)) {
                    slot.heartbeat(generation, epoch, null, clock.instant());
                }
            });
        }

        @Override
        public void onHeartbeatAcknowledged(long sequence) {
            if (sequence < 0L) {
                throw new IllegalArgumentException("sequence must not be negative");
            }
            dispatchObserver(() -> {
                if (isCurrent(slot, generation, epoch)) {
                    slot.heartbeat(generation, epoch, sequence, clock.instant());
                }
            });
        }

        @Override
        public boolean acceptDispatch(GatewayDispatch dispatch) {
            Objects.requireNonNull(dispatch, "dispatch must not be null");
            if (dispatch.sequence() < 0L) {
                throw new IllegalArgumentException("sequence must not be negative");
            }
            // The topology monitor is also held by the pre-swap database fence. Holding it across
            // this one bounded insert closes the check-then-swap window without allowing an old
            // generation to borrow a connection from the new delegate.
            synchronized (topologyMonitor) {
                if (databaseTransitionActive) {
                    return false;
                }
                if (inboxRepository == null) {
                    return true;
                }
                if (!isCurrent(slot, generation, epoch)) {
                    return false;
                }
                Instant receivedAt = clock.instant();
                String platformEventId = dispatch.platformEventId();
                if (platformEventId == null || platformEventId.isBlank()) {
                    platformEventId = slot.fallbackPlatformEventId(
                            generation, dispatch.sessionId(), dispatch.sequence());
                }
                platformEventId = boundedPlatformEventId(platformEventId);
                IncomingEvent event = new IncomingEvent(
                        UUID.randomUUID(),
                        slot.environment(),
                        slot.botId(),
                        dispatch.eventType(),
                        platformEventId,
                        dispatch.rawPayload(),
                        receivedAt);
                try {
                    inboxRepository.insertOrGet(event);
                    return true;
                } catch (RuntimeException exception) {
                    slot.recordFailure(
                            generation,
                            epoch,
                            INBOX_PERSISTENCE_FAILURE,
                            false,
                            receivedAt);
                    LOGGER.warn(
                            "Unable to persist Gateway dispatch for bot {} ({}); retaining the old sequence for retry",
                            slot.botId(),
                            exception.getClass().getSimpleName());
                    return false;
                }
            }
        }

        private String boundedPlatformEventId(String value) {
            if (value.codePointCount(0, value.length()) <= 255) {
                return value;
            }
            try {
                byte[] digest = MessageDigest.getInstance("SHA-256")
                        .digest(value.getBytes(StandardCharsets.UTF_8));
                StringBuilder hex = new StringBuilder("sha256:");
                for (byte item : digest) {
                    hex.append(String.format(Locale.ROOT, "%02x", item));
                }
                return hex.toString();
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException("SHA-256 is unavailable", exception);
            }
        }

        @Override
        public void onDispatch(GatewayDispatch dispatch) {
            Objects.requireNonNull(dispatch, "dispatch must not be null");
            dispatchObserver(() -> {
                if (isCurrent(slot, generation, epoch)) {
                    slot.dispatch(generation, epoch, dispatch.sequence(), clock.instant());
                }
            });
        }

        /** Compatibility path for test/runtime adapters that still publish only a sequence. */
        @Override
        @Deprecated
        public void onDispatch(long sequence) {
            if (sequence < 0L) {
                throw new IllegalArgumentException("sequence must not be negative");
            }
            dispatchObserver(() -> {
                if (isCurrent(slot, generation, epoch)) {
                    slot.dispatch(generation, epoch, sequence, clock.instant());
                }
            });
        }

        @Override
        public void onReconnectScheduled() {
            dispatchObserver(() -> {
                if (isCurrent(slot, generation, epoch)) {
                    slot.reconnect(generation, epoch, clock.instant());
                }
            });
        }

        @Override
        public void onFailure(BotRuntimeFailure failure) {
            Objects.requireNonNull(failure, "failure must not be null");
            dispatchObserver(() -> {
                if (isCurrent(slot, generation, epoch)) {
                    slot.recordFailure(generation, epoch, failure, false, clock.instant());
                }
            });
        }
    }
}
