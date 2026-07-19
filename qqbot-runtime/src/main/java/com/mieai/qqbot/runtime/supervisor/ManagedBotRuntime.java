package com.mieai.qqbot.runtime.supervisor;

import java.util.concurrent.CompletionStage;

/** One isolated bot runtime owned by {@link BotSupervisor}. */
public interface ManagedBotRuntime {
    /**
     * Starts connection discovery and the Gateway lifecycle.
     *
     * <p>An exceptionally completed stage does not imply that this runtime is unusable. A runtime
     * may already have scheduled an internal reconnect and must report its authoritative lifecycle
     * through the observer supplied to its factory.
     */
    CompletionStage<Void> start();

    /**
     * Fences callbacks and reconnects synchronously, then completes when transport cleanup ends.
     */
    CompletionStage<Void> stop();
}
