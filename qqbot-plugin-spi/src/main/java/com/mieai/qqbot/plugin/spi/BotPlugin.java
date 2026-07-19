package com.mieai.qqbot.plugin.spi;

import com.mieai.qqbot.plugin.api.PluginContext;
import com.mieai.qqbot.plugin.api.PluginEvent;
import java.util.concurrent.CompletionStage;

/** Pure Java plugin lifecycle contract. */
public interface BotPlugin {
    default void start(PluginContext context) {}

    CompletionStage<Void> onEvent(PluginEvent event);

    default void stop() {}

    default String handlerId() { return "default"; }
}
