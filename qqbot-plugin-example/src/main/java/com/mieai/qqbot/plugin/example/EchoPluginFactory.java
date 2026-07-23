package com.mieai.qqbot.plugin.example;

import com.mieai.qqbot.plugin.api.EventSubscription;
import com.mieai.qqbot.plugin.api.PluginEvent;
import com.mieai.qqbot.plugin.api.PluginRuntimeContext;
import com.mieai.qqbot.plugin.api.TextMessage;
import com.mieai.qqbot.plugin.spi.BotPlugin;
import com.mieai.qqbot.plugin.spi.BotPluginFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionStage;

/** Minimal production-shaped plugin used for smoke tests and deployment verification. */
public final class EchoPluginFactory implements BotPluginFactory {
    @Override
    public String pluginId() { return "echo"; }

    @Override
    public BotPlugin create(PluginRuntimeContext context) {
        return new EchoPlugin(context);
    }

    private static final class EchoPlugin implements BotPlugin {
        private final PluginRuntimeContext context;
        private final List<EventSubscription> subscriptions = new ArrayList<>();

        private EchoPlugin(PluginRuntimeContext context) { this.context = context; }

        @Override
        public void start() {
            subscriptions.add(context.events().subscribe("commands", Set.of(), this::handleCommand));
            subscriptions.add(context.events().subscribe("audit", Set.of(), this::observeEvent));
        }

        private CompletionStage<Void> handleCommand(PluginEvent event) {
            String content = event.message().flatMap(message -> message.content())
                    .map(String::strip).orElse("");
            if ("/remember".equalsIgnoreCase(content)) {
                context.base().storage().put("echo", "last-event-id", event.platformEventId());
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            }
            if (!"/ping".equalsIgnoreCase(content)) {
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            }
            return context.base().messageSender().enqueue(TextMessage.reply(event, "pong"))
                    .thenApply(ignored -> null);
        }

        private CompletionStage<Void> observeEvent(PluginEvent event) {
            context.base().logger().info("observed event " + event.platformEventId());
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }

        @Override
        public void stop() {
            subscriptions.forEach(EventSubscription::close);
            subscriptions.clear();
        }
    }
}
