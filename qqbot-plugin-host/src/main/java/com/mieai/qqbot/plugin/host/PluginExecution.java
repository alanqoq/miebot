package com.mieai.qqbot.plugin.host;

import java.time.Duration;
import java.util.concurrent.CompletionStage;

/** One cancellable, fenced invocation owned by a plugin binding. */
interface PluginExecution {
    CompletionStage<Void> stage();
    void cancel();
    boolean isDone();
    boolean await(Duration timeout);
}
