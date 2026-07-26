package com.mieai.qqbot.module.host

import com.mieai.qqbot.module.api.ModuleDescriptor
import com.mieai.qqbot.module.api.ModuleServiceKey
import com.mieai.qqbot.module.api.ModuleServiceRegistration
import com.mieai.qqbot.module.spi.ModuleContext

internal class DefaultModuleContext(
    private val descriptor: ModuleDescriptor,
    private val registry: DefaultModuleServiceRegistry,
) : ModuleContext, AutoCloseable {
    private val allowedOwners: Set<String> = descriptor.dependencies.map { it.moduleId }.toSet()
    private var active = true

    override val moduleId: String = descriptor.id

    @Synchronized
    override fun <T : Any> publish(key: ModuleServiceKey<T>, service: T): ModuleServiceRegistration {
        requireActive()
        return registry.publish(descriptor.id, key, service)
    }

    @Synchronized
    override fun <T : Any> find(key: ModuleServiceKey<T>): T? {
        requireActive()
        return registry.find(descriptor.id, key, allowedOwners)
    }

    @Synchronized
    override fun close() {
        if (!active) return
        registry.revokeOwner(descriptor.id)
        active = false
    }

    private fun requireActive() {
        check(active) { "Module context is closed: ${descriptor.id}" }
    }
}
