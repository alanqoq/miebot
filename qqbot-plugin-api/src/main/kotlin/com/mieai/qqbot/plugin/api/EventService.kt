package com.mieai.qqbot.plugin.api


/** Registers named handlers. An empty event-type set subscribes to every event. */
fun interface EventService {
    fun subscribe(handlerId: String, eventTypes: Set<String>, handler: PluginEventHandler): EventSubscription

    companion object {
        fun denied(): EventService = EventService { _, _, _ ->
            throw SecurityException("Plugin event subscription capability is not granted")
        }
    }
}
