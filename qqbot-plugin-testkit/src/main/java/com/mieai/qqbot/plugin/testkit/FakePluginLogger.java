package com.mieai.qqbot.plugin.testkit;

import com.mieai.qqbot.plugin.api.PluginLogger;
import java.util.ArrayList;
import java.util.List;

/** Recording logger that never writes test messages to the process log. */
public final class FakePluginLogger implements PluginLogger {
    private final List<Entry> entries = new ArrayList<>();

    @Override public synchronized void info(String message) { entries.add(new Entry("INFO", message, null)); }
    @Override public synchronized void warn(String message) { entries.add(new Entry("WARN", message, null)); }
    @Override public synchronized void error(String message, Throwable cause) { entries.add(new Entry("ERROR", message, cause)); }

    public synchronized List<Entry> entries() { return List.copyOf(entries); }

    public record Entry(String level, String message, Throwable cause) {}
}
