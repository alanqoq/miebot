package com.mieai.qqbot.plugin.testkit

import com.mieai.qqbot.plugin.api.EventService
import com.mieai.qqbot.plugin.api.EventSubscription
import com.mieai.qqbot.plugin.api.PluginEvent
import com.mieai.qqbot.plugin.api.PluginEventHandler
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/** Recording event capability for plugin unit tests. */
class FakeEventService : EventService, AutoCloseable {
    private val handlers = linkedMapOf<String, Registration>()

    @Synchronized
    override fun subscribe(handlerId: String, eventTypes: Set<String>, handler: PluginEventHandler): EventSubscription {
        validateHandlerId(handlerId)
        val registration = Registration(handlerId, eventTypes.toSet(), handler)
        if (handlers.putIfAbsent(handlerId, registration) != null) {
            throw IllegalArgumentException("handlerId is already registered")
        }
        return registration
    }

    @Synchronized
    fun handlerIds(): Set<String> = handlers.keys.toSet()

    fun emit(event: PluginEvent): CompletionStage<Void> {
        val selected: List<Registration> = synchronized(this) {
            handlers.values.filter { it.matches(event.eventType) }
        }
        var result: CompletionStage<Void> = CompletableFuture.completedFuture(null)
        for (registration in selected) {
            result = result.thenCompose { registration.handler.handle(event) }
        }
        return result
    }

    @Synchronized
    override fun close() {
        // close() removes registrations from the map, so iterate over a snapshot.
        val registrations = handlers.values.toList()
        handlers.clear()
        registrations.forEach(Registration::deactivate)
    }

    private fun validateHandlerId(value: String?) {
        if (value == null || value.isBlank() || value.length > 128 ||
            value.codePoints().anyMatch(Character::isWhitespace)
        ) {
            throw IllegalArgumentException("handlerId is invalid")
        }
    }

    private inner class Registration(
        private val id: String,
        private val eventTypes: Set<String>,
        val handler: PluginEventHandler,
    ) : EventSubscription {
        private var active = true

        fun matches(eventType: String): Boolean = active && (eventTypes.isEmpty() || eventTypes.contains(eventType))

        fun deactivate() {
            active = false
        }

        override val handlerId: String = id

        override val isActive: Boolean
            get() = active

        override fun close() {
            synchronized(this@FakeEventService) {
                deactivate()
                handlers.remove(id, this)
            }
        }
    }
}
