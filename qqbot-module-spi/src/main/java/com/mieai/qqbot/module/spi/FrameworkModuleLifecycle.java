package com.mieai.qqbot.module.spi;

/** Optional startup and shutdown callbacks for one descriptor-backed module JAR. */
public interface FrameworkModuleLifecycle {
    /** Module descriptor ID whose lifecycle this Bean implements. */
    String moduleId();

    /** Called in dependency order after the Spring context is ready. */
    default void start(ModuleContext context) {}

    /** Called in reverse dependency order during shutdown or startup rollback. */
    default void stop() {}
}
