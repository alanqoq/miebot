package com.mieai.qqbot.plugin.api;

/** Minimal logger capability intentionally free of SLF4J/Spring types. */
public interface PluginLogger {
    void info(String message);
    void warn(String message);
    void error(String message, Throwable cause);
}
