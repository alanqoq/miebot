package com.mieai.qqbot.app.plugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mieai.qqbot.persistence.bot.BotRepository;
import com.mieai.qqbot.persistence.inbox.EventInboxRepository;
import com.mieai.qqbot.persistence.outbox.OutboxRepository;
import com.mieai.qqbot.persistence.plugin.BotPluginBindingRepository;
import com.mieai.qqbot.persistence.plugin.PluginArtifactRepository;
import com.mieai.qqbot.persistence.plugin.PluginDeliveryRepository;
import com.mieai.qqbot.persistence.plugin.PluginStorageRepository;
import com.mieai.qqbot.client.MediaAssetStore;
import com.mieai.qqbot.persistence.lease.BotLeaseRepository;
import com.mieai.qqbot.plugin.host.Pf4jPluginHost;
import com.mieai.qqbot.plugin.host.PluginRuntimeService;
import java.time.Clock;
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
            PluginStorageRepository storage, MediaAssetStore mediaAssetStore, ObjectMapper objectMapper) {
        return new Pf4jPluginHost(properties.getDirectory(), artifacts, bots, outbox, storage,
                mediaAssetStore, objectMapper, Clock.systemUTC(), properties.getBindingQueueCapacity(),
                properties.getShutdownTimeout());
    }

    @Bean(destroyMethod = "close")
    PluginRuntimeService pluginRuntimeService(PluginRuntimeProperties properties,
            Pf4jPluginHost host, EventInboxRepository inbox, BotPluginBindingRepository bindings,
            PluginDeliveryRepository deliveries, BotLeaseRepository botLeases,
            @Qualifier("qqbotInstanceId") String instanceId,
            @Qualifier("pluginScheduler") ScheduledExecutorService pluginScheduler) {
        return new PluginRuntimeService(host, inbox, bindings, deliveries, pluginScheduler,
                Clock.systemUTC(), properties.getPollInterval(), properties.getLeaseDuration(),
                properties.getExecutionTimeout(), properties.getCancellationGrace(),
                properties.getMaxAttempts(), properties.getBatchSize(), botLeases, instanceId);
    }

    @Bean
    PluginRuntimeLifecycle pluginRuntimeLifecycle(PluginRuntimeService runtime, PluginRuntimeProperties properties) {
        return new PluginRuntimeLifecycle(runtime, properties.isEnabled());
    }
}
