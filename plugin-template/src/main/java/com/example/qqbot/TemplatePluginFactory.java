package com.example.qqbot;

import com.mieai.qqbot.plugin.api.EventSubscription;
import com.mieai.qqbot.plugin.api.PluginEvent;
import com.mieai.qqbot.plugin.api.PluginRuntimeContext;
import com.mieai.qqbot.plugin.api.TextMessage;
import com.mieai.qqbot.plugin.spi.BotPlugin;
import com.mieai.qqbot.plugin.spi.BotPluginFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Copy this class and replace the command logic with your plugin behavior. */
public final class TemplatePluginFactory implements BotPluginFactory {
    @Override
    public String pluginId() {
        return "template";
    }

    @Override
    public BotPlugin create(PluginRuntimeContext context) {
        return new TemplatePlugin(context);
    }

    private static final class TemplatePlugin implements BotPlugin {
        private final PluginRuntimeContext context;
        private final List<EventSubscription> subscriptions = new ArrayList<>();

        private TemplatePlugin(PluginRuntimeContext context) {
            this.context = context;
        }

        @Override
        public void start() {
            subscriptions.add(context.events().subscribe("commands", Set.of(), this::handleCommand));
            // Add more named subscriptions when a plugin has independent workflows.
            subscriptions.add(context.events().subscribe("audit", Set.of(), this::audit));
        }

        private CompletionStage<Void> handleCommand(PluginEvent event) {
            String content = event.message().flatMap(message -> message.content())
                    .map(String::strip).orElse("");
            if (!"/hello".equalsIgnoreCase(content)) {
                return CompletableFuture.completedFuture(null);
            }
            return context.base().messageSender().enqueue(TextMessage.reply(event, "hello"))
                    .thenApply(ignored -> null);
        }

        private CompletionStage<Void> audit(PluginEvent event) {
            context.base().logger().info("received " + event.platformEventId());
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void stop() {
            subscriptions.forEach(EventSubscription::close);
            subscriptions.clear();
        }
    }
}
