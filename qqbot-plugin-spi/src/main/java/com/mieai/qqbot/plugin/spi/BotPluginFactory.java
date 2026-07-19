package com.mieai.qqbot.plugin.spi;

import com.mieai.qqbot.plugin.api.PluginContext;

/** ServiceLoader entry point supplied by a trusted plugin JAR. */
public interface BotPluginFactory {
    String pluginId();
    BotPlugin create(PluginContext context);
}
