package com.mieai.qqbot.module.spi

import com.mieai.qqbot.module.api.ModuleServiceKey
import com.mieai.qqbot.module.api.ModuleServiceRegistration

/** Lifecycle-scoped service exchange available to one framework module. */
interface ModuleContext {
    val moduleId: String

    fun <T : Any> publish(key: ModuleServiceKey<T>, service: T): ModuleServiceRegistration

    fun <T : Any> find(key: ModuleServiceKey<T>): T?

    fun <T : Any> require(key: ModuleServiceKey<T>): T = find(key)
        ?: throw IllegalStateException("Required module service is unavailable: ${key.name}")
}
