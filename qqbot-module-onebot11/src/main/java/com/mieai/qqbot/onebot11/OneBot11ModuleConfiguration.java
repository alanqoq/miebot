package com.mieai.qqbot.onebot11;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mieai.qqbot.module.spi.FrameworkModuleLifecycle;
import com.mieai.qqbot.modules.database.DatabaseTransitionParticipant;
import com.mieai.qqbot.persistence.bot.BotRepository;
import com.mieai.qqbot.runtime.event.BotGatewayEventSource;
import com.mieai.qqbot.runtime.openapi.BotOpenApiClientProvider;
import com.mieai.qqbot.runtime.security.AesGcmConfigurationSecretCipher;
import com.mieai.qqbot.runtime.security.KeyProvider;
import com.mieai.qqbot.runtime.supervisor.BotSupervisor;
import com.mieai.qqbot.onebot11.config.OneBot11ConfigRepository;
import com.mieai.qqbot.onebot11.config.OneBot11ConfigurationService;
import com.mieai.qqbot.onebot11.mapping.OneBotEntityIdRepository;
import com.mieai.qqbot.onebot11.mapping.OneBotMessageRepository;
import com.mieai.qqbot.onebot11.protocol.OneBotActionService;
import com.mieai.qqbot.onebot11.protocol.OneBotEventMapper;
import com.mieai.qqbot.onebot11.protocol.OneBotMediaCache;
import com.mieai.qqbot.onebot11.protocol.OneBotMessageCodec;
import com.mieai.qqbot.onebot11.transport.OneBotRuntimeManager;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

@AutoConfiguration
@ConditionalOnProperty(name = "qqbot.modules.available.onebot11", havingValue = "true")
@EnableConfigurationProperties(OneBot11Properties.class)
@Import({OneBot11SettingsController.class, OneBot11ExceptionHandler.class})
public class OneBot11ModuleConfiguration {
    @Bean
    OneBot11ConfigRepository oneBot11ConfigRepository(DataSource dataSource) {
        return new OneBot11ConfigRepository(dataSource);
    }

    @Bean
    OneBotEntityIdRepository oneBotEntityIdRepository(DataSource dataSource) {
        return new OneBotEntityIdRepository(dataSource);
    }

    @Bean
    OneBotMessageRepository oneBotMessageRepository(DataSource dataSource) {
        return new OneBotMessageRepository(dataSource);
    }

    @Bean
    OneBot11ConfigurationService oneBot11ConfigurationService(
            OneBot11ConfigRepository configs,
            BotRepository bots,
            KeyProvider keyProvider) {
        return new OneBot11ConfigurationService(
                configs, bots, new AesGcmConfigurationSecretCipher(keyProvider));
    }

    @Bean
    OneBotMessageCodec oneBotMessageCodec(ObjectMapper objectMapper) {
        return new OneBotMessageCodec(objectMapper);
    }

    @Bean
    OneBotMediaCache oneBotMediaCache(OneBot11Properties properties) {
        return new OneBotMediaCache(properties.getCacheDirectory());
    }

    @Bean
    OneBotEventMapper oneBotEventMapper(
            ObjectMapper objectMapper,
            BotRepository bots,
            OneBotEntityIdRepository entityIds,
            OneBotMessageRepository messages,
            OneBotMessageCodec messageCodec) {
        return new OneBotEventMapper(
                objectMapper, bots, entityIds, messages, messageCodec);
    }

    @Bean(destroyMethod = "close")
    OneBotActionService oneBotActionService(
            ObjectMapper objectMapper,
            BotOpenApiClientProvider clients,
            BotRepository bots,
            BotSupervisor supervisor,
            OneBotEntityIdRepository entityIds,
            OneBotMessageRepository messages,
            OneBotMessageCodec messageCodec,
            OneBotMediaCache mediaCache,
            OneBotEventMapper eventMapper) {
        return new OneBotActionService(objectMapper, clients, bots, supervisor,
                entityIds, messages, messageCodec, mediaCache, eventMapper);
    }

    @Bean
    OneBotRuntimeManager oneBotRuntimeManager(
            ObjectMapper objectMapper,
            OneBot11ConfigurationService configurations,
            BotGatewayEventSource gatewayEvents,
            BotSupervisor supervisor,
            OneBotEventMapper eventMapper,
            OneBotActionService actions) {
        return new OneBotRuntimeManager(
                objectMapper, configurations, gatewayEvents, supervisor, eventMapper, actions);
    }

    @Bean
    FrameworkModuleLifecycle oneBot11Lifecycle(OneBotRuntimeManager runtimes) {
        return new FrameworkModuleLifecycle() {
            @Override public String moduleId() { return "onebot11"; }
            @Override public void start(com.mieai.qqbot.module.spi.ModuleContext context) {
                runtimes.start();
            }
            @Override public void stop() { runtimes.close(); }
        };
    }

    @Bean
    DatabaseTransitionParticipant oneBot11DatabaseTransition(OneBotRuntimeManager runtimes) {
        return new DatabaseTransitionParticipant() {
            @Override public int beforeOrder() { return 275; }
            @Override public int afterOrder() { return 275; }
            @Override public void beforeDatabaseChange() { runtimes.beforeDatabaseChange(); }
            @Override public void afterDatabaseChange() { runtimes.afterDatabaseChange(); }
        };
    }
}
