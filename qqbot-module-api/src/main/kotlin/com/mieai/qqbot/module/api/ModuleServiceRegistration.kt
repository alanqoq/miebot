package com.mieai.qqbot.module.api

/** Revocable ownership handle for a service published by a framework module. */
interface ModuleServiceRegistration : AutoCloseable {
    val ownerModuleId: String

    val key: ModuleServiceKey<*>

    val isActive: Boolean

    override fun close()
}
