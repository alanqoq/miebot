package com.mieai.qqbot.module.spi

import com.mieai.qqbot.module.api.ModuleDescriptor

/** Optional lifecycle entry point supplied by a startup-loaded framework module JAR. */
interface FrameworkModule {
    val descriptor: ModuleDescriptor

    fun start(context: ModuleContext) {}

    fun stop() {}

    companion object {
        fun declarative(value: ModuleDescriptor): FrameworkModule = object : FrameworkModule {
            override val descriptor = value
        }
    }
}
