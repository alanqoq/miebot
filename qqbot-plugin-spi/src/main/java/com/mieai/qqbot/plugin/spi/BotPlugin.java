package com.mieai.qqbot.plugin.spi;

import com.mieai.qqbot.plugin.api.PluginContext;
import com.mieai.qqbot.plugin.api.PluginEvent;
import com.mieai.qqbot.plugin.api.PluginRuntimeContext;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Pure Java plugin lifecycle contract. */
public interface BotPlugin {
    default void start(PluginContext context) {}

    /** Starts a V2 plugin with its complete binding-scoped capability context. */
    default void start(PluginRuntimeContext context) {
        start(context.base());
    }

    /** V2 EventService handlers may be the only event entry points. */
    default CompletionStage<Void> onEvent(PluginEvent event) {
        return CompletableFuture.completedFuture(null);
    }

    default void stop() {}

    default String handlerId() { return "default"; }
}
