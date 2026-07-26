package com.mieai.qqbot.module.spi

/** Optional startup and shutdown callbacks for one descriptor-backed module JAR. */
interface FrameworkModuleLifecycle {
    /** Module descriptor ID whose lifecycle this Bean implements. */
    val moduleId: String

    /** Called in dependency order after the Spring context is ready. */
    fun start(context: ModuleContext) {}

    /** Called in reverse dependency order during shutdown or startup rollback. */
    fun stop() {}
}
