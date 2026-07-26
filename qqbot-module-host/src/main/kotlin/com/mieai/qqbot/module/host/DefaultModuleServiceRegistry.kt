package com.mieai.qqbot.module.host

import com.mieai.qqbot.module.api.ModuleServiceKey
import com.mieai.qqbot.module.api.ModuleServiceRegistration
internal class DefaultModuleServiceRegistry {
    private val entries = mutableMapOf<ModuleServiceKey<*>, Entry<*>>()

    @Synchronized
    fun <T : Any> publish(ownerModuleId: String, key: ModuleServiceKey<T>, service: T): ModuleServiceRegistration {
        require(key.type.isInstance(service)) { "service does not implement ${key.type.name}" }
        check(!entries.containsKey(key)) { "Module service is already registered: ${key.name}" }
        val registration = Registration(ownerModuleId, key)
        entries[key] = Entry(ownerModuleId, service, registration)
        return registration
    }

    @Synchronized
    fun <T : Any> find(requesterModuleId: String, key: ModuleServiceKey<T>, allowedOwners: Set<String>): T? {
        val entry = entries[key] ?: return null
        check(requesterModuleId == entry.ownerModuleId || allowedOwners.contains(entry.ownerModuleId)) {
            "Module $requesterModuleId did not declare a dependency on service owner ${entry.ownerModuleId}"
        }
        return key.type.cast(entry.service)
    }

    @Synchronized
    fun revokeOwner(ownerModuleId: String) {
        entries.values.filter { it.ownerModuleId == ownerModuleId }
            .map { it.registration }
            .toList()
            .forEach { it.close() }
    }

    private data class Entry<T : Any>(val ownerModuleId: String, val service: T, val registration: Registration)

    private inner class Registration(
        private val owner: String,
        private val serviceKey: ModuleServiceKey<*>,
    ) : ModuleServiceRegistration {
        @Volatile private var active = true
        override val ownerModuleId: String = owner
        override val key: ModuleServiceKey<*> = serviceKey
        override val isActive: Boolean
            get() = active

        override fun close() {
            synchronized(this@DefaultModuleServiceRegistry) {
                if (!active) return
                val current = entries[serviceKey]
                if (current?.registration === this) entries.remove(serviceKey)
                active = false
            }
        }
    }
}
