package com.mieai.qqbot.runtime.supervisor;

import com.mieai.qqbot.persistence.bot.StoredBot;

/** Creates an isolated runtime from the latest persisted configuration and encrypted credential. */
@FunctionalInterface
public interface BotRuntimeFactory {
    /**
     * Creates a runtime without performing blocking network I/O. Network discovery belongs in
     * {@link ManagedBotRuntime#start()} so the supervisor can install and fence the runtime first.
     */
    ManagedBotRuntime create(StoredBot configuration, BotRuntimeObserver observer);
}
