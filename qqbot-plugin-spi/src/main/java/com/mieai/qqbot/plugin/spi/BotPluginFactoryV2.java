package com.mieai.qqbot.plugin.spi;

import com.mieai.qqbot.plugin.api.PluginContext;
import com.mieai.qqbot.plugin.api.PluginRuntimeContext;

/** Extended factory for scheduler, HTTP, media, configuration and multi-handler capabilities. */
public interface BotPluginFactoryV2 extends BotPluginFactory {
    BotPlugin create(PluginRuntimeContext context);

    @Override
    default BotPlugin create(PluginContext context) {
        return create(PluginRuntimeContext.legacy(context, 0L));
    }
}
