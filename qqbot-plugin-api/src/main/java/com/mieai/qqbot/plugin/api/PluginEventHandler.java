package com.mieai.qqbot.plugin.api;

import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface PluginEventHandler {
    CompletionStage<Void> handle(PluginEvent event);
}
