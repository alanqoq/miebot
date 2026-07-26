package com.mieai.qqbot.plugin.host

import java.time.Duration
import java.util.concurrent.CompletionStage

/** One cancellable, fenced invocation owned by a plugin binding. */
interface PluginExecution {
    fun stage(): CompletionStage<Void>

    fun cancel()

    fun isDone(): Boolean

    fun await(timeout: Duration): Boolean
}
