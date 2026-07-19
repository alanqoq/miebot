package com.mieai.qqbot.plugin.host;

import com.mieai.qqbot.plugin.spi.BotPluginFactory;
import java.util.List;
import java.util.ServiceLoader;
import org.pf4j.Plugin;
import org.pf4j.PluginWrapper;

/** PF4J lifecycle adapter; plugin authors only implement the pure Java SPI service. */
public final class Pf4jPluginBridge extends Plugin {
    private BotPluginFactory factory;

    public Pf4jPluginBridge(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public void start() {
        ClassLoader pluginClassLoader = getWrapper().getPluginClassLoader();
        List<BotPluginFactory> factories = ServiceLoader.load(BotPluginFactory.class, pluginClassLoader)
                .stream()
                .filter(provider -> provider.type().getClassLoader() == pluginClassLoader)
                .map(ServiceLoader.Provider::get)
                .toList();
        if (factories.size() != 1) {
            throw new IllegalStateException("Plugin must provide exactly one BotPluginFactory service");
        }
        factory = factories.getFirst();
    }

    @Override
    public void stop() {
        factory = null;
    }

    BotPluginFactory factory() {
        if (factory == null) throw new IllegalStateException("Plugin has not been started");
        return factory;
    }
}
