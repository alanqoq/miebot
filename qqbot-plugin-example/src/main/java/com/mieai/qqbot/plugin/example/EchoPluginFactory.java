package com.mieai.qqbot.plugin.example;

import com.mieai.qqbot.plugin.api.PluginContext;
import com.mieai.qqbot.plugin.api.PluginEvent;
import com.mieai.qqbot.plugin.api.TextMessage;
import com.mieai.qqbot.plugin.spi.BotPlugin;
import com.mieai.qqbot.plugin.spi.BotPluginFactory;
import java.util.concurrent.CompletionStage;

/** Minimal production-shaped plugin used for smoke tests and deployment verification. */
public final class EchoPluginFactory implements BotPluginFactory {
    @Override
    public String pluginId() { return "echo"; }

    @Override
    public BotPlugin create(PluginContext context) {
        return new EchoPlugin(context);
    }

    private static final class EchoPlugin implements BotPlugin {
        private final PluginContext context;

        private EchoPlugin(PluginContext context) { this.context = context; }

        @Override
        public CompletionStage<Void> onEvent(PluginEvent event) {
            String content = event.message().flatMap(message -> message.content())
                    .map(String::strip).orElse("");
            if ("/remember".equalsIgnoreCase(content)) {
                context.storage().put("echo", "last-event-id", event.platformEventId());
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            }
            if (!"/ping".equalsIgnoreCase(content)) {
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            }
            return context.messageSender().enqueue(TextMessage.reply(event, "pong"))
                    .thenApply(ignored -> null);
        }
    }
}
