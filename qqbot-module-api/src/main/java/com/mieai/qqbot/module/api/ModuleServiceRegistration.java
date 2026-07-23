package com.mieai.qqbot.module.api;

/** Revocable ownership handle for a service published by a framework module. */
public interface ModuleServiceRegistration extends AutoCloseable {
    String ownerModuleId();
    ModuleServiceKey<?> key();
    boolean isActive();
    @Override void close();
}
