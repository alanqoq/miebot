package com.mieai.qqbot.onebot11.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mieai.qqbot.domain.bot.BotId;
import com.mieai.qqbot.runtime.event.BotGatewayEvent;
import com.mieai.qqbot.runtime.event.BotGatewayEventSource;
import com.mieai.qqbot.runtime.supervisor.BotSupervisor;
import com.mieai.qqbot.onebot11.config.OneBot11ConfigurationService;
import com.mieai.qqbot.onebot11.protocol.OneBotActionService;
import com.mieai.qqbot.onebot11.protocol.OneBotEventMapper;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Owns transports only on the application instance currently supervising each bot. */
public final class OneBotRuntimeManager implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(OneBotRuntimeManager.class);

    private final ObjectMapper objectMapper;
    private final OneBot11ConfigurationService configurations;
    private final BotGatewayEventSource gatewayEvents;
    private final BotSupervisor supervisor;
    private final OneBotEventMapper eventMapper;
    private final OneBotActionService actions;
    private final OneBotMetaEventFactory metaEvents;
    private final Map<BotId, OneBotBotRuntime> runtimes = new ConcurrentHashMap<>();
    private final Map<BotId, String> startupErrors = new ConcurrentHashMap<>();
    private final ScheduledExecutorService reconciler = Executors.newSingleThreadScheduledExecutor(
            runnable -> {
                Thread thread = new Thread(runnable, "onebot11-reconciler");
                thread.setDaemon(true);
                return thread;
            });
    private volatile AutoCloseable eventSubscription;
    private volatile boolean running;
    private volatile boolean databaseTransition;

    public OneBotRuntimeManager(
            ObjectMapper objectMapper,
            OneBot11ConfigurationService configurations,
            BotGatewayEventSource gatewayEvents,
            BotSupervisor supervisor,
            OneBotEventMapper eventMapper,
            OneBotActionService actions) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.configurations = Objects.requireNonNull(configurations, "configurations must not be null");
        this.gatewayEvents = Objects.requireNonNull(gatewayEvents, "gatewayEvents must not be null");
        this.supervisor = Objects.requireNonNull(supervisor, "supervisor must not be null");
        this.eventMapper = Objects.requireNonNull(eventMapper, "eventMapper must not be null");
        this.actions = Objects.requireNonNull(actions, "actions must not be null");
        metaEvents = new OneBotMetaEventFactory(objectMapper, supervisor);
        actions.setRestartHandler(this::restart);
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        eventSubscription = gatewayEvents.subscribe(this::onGatewayEvent);
        reconciler.scheduleWithFixedDelay(
                this::reconcileAllSafely, 0L, 3L, TimeUnit.SECONDS);
    }

    public void reconcile(BotId botId) {
        Objects.requireNonNull(botId, "botId must not be null");
        reconciler.execute(() -> reconcileOneSafely(botId, false));
    }

    public OneBotTransportStatus status(BotId botId) {
        OneBotBotRuntime runtime = runtimes.get(botId);
        if (runtime != null) return runtime.status();
        String error = startupErrors.get(botId);
        if (error != null) return OneBotTransportStatus.failed(error);
        if (configurations.isEnabled(botId)) return OneBotTransportStatus.waiting();
        return OneBotTransportStatus.disabled();
    }

    public void restart(BotId botId) {
        if (!running) return;
        reconciler.execute(() -> reconcileOneSafely(botId, true));
    }

    public void beforeDatabaseChange() {
        databaseTransition = true;
        stopAll();
    }

    public void afterDatabaseChange() {
        databaseTransition = false;
        if (running) reconciler.execute(this::reconcileAllSafely);
    }

    private void reconcileAllSafely() {
        if (!running || databaseTransition) return;
        try {
            Set<BotId> desired = new HashSet<>();
            desired.addAll(configurations.enabledBotIds());
            desired.addAll(runtimes.keySet());
            desired.forEach(botId -> reconcileOneSafely(botId, false));
        } catch (RuntimeException exception) {
            LOGGER.warn("Unable to reconcile OneBot transports ({})",
                    exception.getClass().getSimpleName());
        }
    }

    private void reconcileOneSafely(BotId botId, boolean force) {
        try {
            reconcileOne(botId, force);
        } catch (RuntimeException exception) {
            startupErrors.put(botId, "OneBot transport could not start");
            LOGGER.warn("Unable to reconcile OneBot transport for bot {} ({})",
                    botId, exception.getClass().getSimpleName());
        }
    }

    private void reconcileOne(BotId botId, boolean force) {
        if (!running || databaseTransition) return;
        var desired = configurations.resolve(botId);
        boolean ownsBot = supervisor.status(botId)
                .map(status -> status.desiredEnabled() && status.state().isRunning())
                .orElse(false);
        OneBotBotRuntime current = runtimes.get(botId);
        if (desired.isEmpty() || !ownsBot) {
            stop(botId, current);
            startupErrors.remove(botId);
            return;
        }
        if (!force && current != null
                && current.revision() == desired.orElseThrow().config().revision()) {
            return;
        }
        stop(botId, current);
        OneBotBotRuntime replacement = new OneBotBotRuntime(
                objectMapper,
                desired.orElseThrow(),
                eventMapper.selfId(botId),
                metaEvents,
                actions);
        replacement.start();
        runtimes.put(botId, replacement);
        startupErrors.remove(botId);
    }

    private void onGatewayEvent(BotGatewayEvent event) {
        OneBotBotRuntime runtime = runtimes.get(event.botId());
        if (runtime == null) return;
        try {
            eventMapper.map(event).ifPresent(runtime::publish);
        } catch (RuntimeException exception) {
            LOGGER.warn("Unable to convert Gateway event {} for OneBot bot {} ({})",
                    event.dispatch().eventType(), event.botId(), exception.getClass().getSimpleName());
        }
    }

    private void stop(BotId botId, OneBotBotRuntime runtime) {
        if (runtime != null && runtimes.remove(botId, runtime)) {
            runtime.close();
        }
    }

    private void stopAll() {
        runtimes.forEach(this::stop);
    }

    @Override
    public synchronized void close() {
        if (!running) return;
        running = false;
        AutoCloseable subscription = eventSubscription;
        eventSubscription = null;
        if (subscription != null) {
            try {
                subscription.close();
            } catch (Exception exception) {
                LOGGER.debug("Unable to close OneBot Gateway subscription", exception);
            }
        }
        reconciler.shutdownNow();
        stopAll();
    }
}
