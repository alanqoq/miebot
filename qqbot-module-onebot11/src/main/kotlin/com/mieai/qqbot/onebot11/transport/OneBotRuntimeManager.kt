package com.mieai.qqbot.onebot11.transport

import com.fasterxml.jackson.databind.ObjectMapper
import com.mieai.qqbot.domain.bot.BotId
import com.mieai.qqbot.onebot11.config.OneBot11ConfigurationService
import com.mieai.qqbot.onebot11.protocol.OneBotActionService
import com.mieai.qqbot.onebot11.protocol.OneBotEventMapper
import com.mieai.qqbot.runtime.event.BotGatewayEvent
import com.mieai.qqbot.runtime.event.BotGatewayEventSource
import com.mieai.qqbot.runtime.supervisor.BotSupervisor
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory

/** Owns transports only on the application instance currently supervising each bot. */
class OneBotRuntimeManager(
    private val objectMapper: ObjectMapper,
    private val configurations: OneBot11ConfigurationService,
    private val gatewayEvents: BotGatewayEventSource,
    private val supervisor: BotSupervisor,
    private val eventMapper: OneBotEventMapper,
    private val actions: OneBotActionService,
) : AutoCloseable {
    private val metaEvents = OneBotMetaEventFactory(objectMapper, supervisor)
    private val runtimes = ConcurrentHashMap<BotId, OneBotBotRuntime>()
    private val startupErrors = ConcurrentHashMap<BotId, String>()
    private val reconciler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor {
        runnable -> Thread(runnable, "onebot11-reconciler").apply { isDaemon = true }
    }

    @Volatile
    private var eventSubscription: AutoCloseable? = null

    @Volatile
    private var running = false

    @Volatile
    private var databaseTransition = false

    init {
        actions.setRestartHandler(::restart)
    }

    @Synchronized
    fun start() {
        if (running) return
        running = true
        eventSubscription = gatewayEvents.subscribe(::onGatewayEvent)
        reconciler.scheduleWithFixedDelay(::reconcileAllSafely, 0L, 3L, TimeUnit.SECONDS)
    }

    fun reconcile(botId: BotId) {
        reconciler.execute { reconcileOneSafely(botId, false) }
    }

    fun status(botId: BotId): OneBotTransportStatus {
        runtimes[botId]?.let { return it.status() }
        startupErrors[botId]?.let { return OneBotTransportStatus.failed(it) }
        if (configurations.isEnabled(botId)) return OneBotTransportStatus.waiting()
        return OneBotTransportStatus.disabled()
    }

    fun restart(botId: BotId) {
        if (!running) return
        reconciler.execute { reconcileOneSafely(botId, true) }
    }

    fun beforeDatabaseChange() {
        databaseTransition = true
        stopAll()
    }

    fun afterDatabaseChange() {
        databaseTransition = false
        if (running) reconciler.execute(::reconcileAllSafely)
    }

    private fun reconcileAllSafely() {
        if (!running || databaseTransition) return
        try {
            val desired = mutableSetOf<BotId>()
            desired.addAll(configurations.enabledBotIds())
            desired.addAll(runtimes.keys)
            desired.forEach { botId -> reconcileOneSafely(botId, false) }
        } catch (exception: RuntimeException) {
            LOGGER.warn(
                "Unable to reconcile OneBot transports ({})",
                exception.javaClass.simpleName,
            )
        }
    }

    private fun reconcileOneSafely(botId: BotId, force: Boolean) {
        try {
            reconcileOne(botId, force)
        } catch (exception: RuntimeException) {
            startupErrors[botId] = "OneBot transport could not start"
            LOGGER.warn(
                "Unable to reconcile OneBot transport for bot {} ({})",
                botId,
                exception.javaClass.simpleName,
            )
        }
    }

    private fun reconcileOne(botId: BotId, force: Boolean) {
        if (!running || databaseTransition) return
        val desired = configurations.resolve(botId)
        val ownsBot = supervisor.status(botId)
            ?.let { status -> status.desiredEnabled && status.state.isRunning() }
            ?: false
        val current = runtimes[botId]
        if (desired == null || !ownsBot) {
            stop(botId, current)
            startupErrors.remove(botId)
            return
        }
        val resolved = desired
        if (!force && current != null && current.revision() == resolved.config.revision) {
            return
        }
        stop(botId, current)
        val replacement = OneBotBotRuntime(
            objectMapper,
            resolved,
            eventMapper.selfId(botId),
            metaEvents,
            actions,
        )
        replacement.start()
        runtimes[botId] = replacement
        startupErrors.remove(botId)
    }

    private fun onGatewayEvent(event: BotGatewayEvent) {
        val runtime = runtimes[event.botId] ?: return
        try {
            eventMapper.map(event)?.let(runtime::publish)
        } catch (exception: RuntimeException) {
            LOGGER.warn(
                "Unable to convert Gateway event {} for OneBot bot {} ({})",
                event.dispatch.eventType,
                event.botId,
                exception.javaClass.simpleName,
            )
        }
    }

    private fun stop(botId: BotId, runtime: OneBotBotRuntime?) {
        if (runtime != null && runtimes.remove(botId, runtime)) {
            runtime.close()
        }
    }

    private fun stopAll() {
        runtimes.forEach(::stop)
    }

    @Synchronized
    override fun close() {
        if (!running) return
        running = false
        val subscription = eventSubscription
        eventSubscription = null
        if (subscription != null) {
            try {
                subscription.close()
            } catch (exception: Exception) {
                LOGGER.debug("Unable to close OneBot Gateway subscription", exception)
            }
        }
        reconciler.shutdownNow()
        stopAll()
    }

    companion object {
        private val LOGGER = LoggerFactory.getLogger(OneBotRuntimeManager::class.java)
    }
}
