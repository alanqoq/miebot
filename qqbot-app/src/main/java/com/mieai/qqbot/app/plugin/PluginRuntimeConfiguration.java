package com.mieai.qqbot.app.plugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mieai.qqbot.persistence.bot.BotRepository;
import com.mieai.qqbot.persistence.inbox.EventInboxRepository;
import com.mieai.qqbot.persistence.outbox.OutboxRepository;
import com.mieai.qqbot.persistence.plugin.BotPluginBindingRepository;
import com.mieai.qqbot.persistence.plugin.PluginArtifactRepository;
import com.mieai.qqbot.persistence.plugin.PluginDeliveryRepository;
import com.mieai.qqbot.persistence.plugin.PluginStorageRepository;
import com.mieai.qqbot.plugin.host.Pf4jPluginHost;
import com.mieai.qqbot.plugin.host.PluginRuntimeService;
import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PluginRuntimeProperties.class)
public class PluginRuntimeConfiguration {
    @Bean(destroyMethod = "shutdownNow")
    ExecutorService pluginExecutor(PluginRuntimeProperties properties) {
        return Executors.newFixedThreadPool(8, runnable -> {
            Thread thread = new Thread(runnable, "qqbot-plugin-exec");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Bean(destroyMethod = "shutdownNow")
    ScheduledExecutorService pluginScheduler() {
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "qqbot-plugin-worker");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Bean(destroyMethod = "close")
    Pf4jPluginHost pf4jPluginHost(PluginRuntimeProperties properties,
            PluginArtifactRepository artifacts, BotRepository bots, OutboxRepository outbox,
            PluginStorageRepository storage, ObjectMapper objectMapper,
            @Qualifier("pluginExecutor") ExecutorService pluginExecutor) {
        return new Pf4jPluginHost(properties.getDirectory(), artifacts, bots, outbox, storage,
                objectMapper, Clock.systemUTC(), pluginExecutor);
    }

    @Bean(destroyMethod = "close")
    PluginRuntimeService pluginRuntimeService(PluginRuntimeProperties properties,
            Pf4jPluginHost host, EventInboxRepository inbox, BotPluginBindingRepository bindings,
            PluginDeliveryRepository deliveries, @Qualifier("pluginScheduler") ScheduledExecutorService pluginScheduler) {
        return new PluginRuntimeService(host, inbox, bindings, deliveries, pluginScheduler,
                Clock.systemUTC(), properties.getPollInterval(), properties.getLeaseDuration(),
                properties.getExecutionTimeout(), properties.getMaxAttempts(), properties.getBatchSize());
    }

    @Bean
    PluginRuntimeLifecycle pluginRuntimeLifecycle(PluginRuntimeService runtime, PluginRuntimeProperties properties) {
        return new PluginRuntimeLifecycle(runtime, properties.isEnabled());
    }
}
