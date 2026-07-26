package com.mieai.qqbot.onebot11

import com.fasterxml.jackson.databind.ObjectMapper
import com.mieai.qqbot.module.spi.FrameworkModuleLifecycle
import com.mieai.qqbot.module.spi.ModuleContext
import com.mieai.qqbot.modules.database.DatabaseTransitionParticipant
import com.mieai.qqbot.onebot11.config.OneBot11ConfigRepository
import com.mieai.qqbot.onebot11.config.OneBot11ConfigurationService
import com.mieai.qqbot.onebot11.mapping.OneBotEntityIdRepository
import com.mieai.qqbot.onebot11.mapping.OneBotMessageRepository
import com.mieai.qqbot.onebot11.protocol.OneBotActionService
import com.mieai.qqbot.onebot11.protocol.OneBotEventMapper
import com.mieai.qqbot.onebot11.protocol.OneBotMediaCache
import com.mieai.qqbot.onebot11.protocol.OneBotMessageCodec
import com.mieai.qqbot.onebot11.transport.OneBotRuntimeManager
import com.mieai.qqbot.persistence.bot.BotRepository
import com.mieai.qqbot.runtime.event.BotGatewayEventSource
import com.mieai.qqbot.runtime.openapi.BotOpenApiClientProvider
import com.mieai.qqbot.runtime.security.AesGcmConfigurationSecretCipher
import com.mieai.qqbot.runtime.security.KeyProvider
import com.mieai.qqbot.runtime.supervisor.BotSupervisor
import javax.sql.DataSource
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import

@AutoConfiguration
@ConditionalOnProperty(name = ["qqbot.modules.available.onebot11"], havingValue = "true")
@EnableConfigurationProperties(OneBot11Properties::class)
@Import(OneBot11SettingsController::class, OneBot11ExceptionHandler::class)
class OneBot11ModuleConfiguration {
    @Bean
    fun oneBot11ConfigRepository(dataSource: DataSource) = OneBot11ConfigRepository(dataSource)

    @Bean
    fun oneBotEntityIdRepository(dataSource: DataSource) = OneBotEntityIdRepository(dataSource)

    @Bean
    fun oneBotMessageRepository(dataSource: DataSource) = OneBotMessageRepository(dataSource)

    @Bean
    fun oneBot11ConfigurationService(
        configs: OneBot11ConfigRepository,
        bots: BotRepository,
        keyProvider: KeyProvider,
    ) = OneBot11ConfigurationService(
        configs,
        bots,
        AesGcmConfigurationSecretCipher(keyProvider),
    )

    @Bean
    fun oneBotMessageCodec(objectMapper: ObjectMapper) = OneBotMessageCodec(objectMapper)

    @Bean
    fun oneBotMediaCache(properties: OneBot11Properties) =
        OneBotMediaCache(properties.cacheDirectory)

    @Bean
    fun oneBotEventMapper(
        objectMapper: ObjectMapper,
        bots: BotRepository,
        entityIds: OneBotEntityIdRepository,
        messages: OneBotMessageRepository,
        messageCodec: OneBotMessageCodec,
    ) = OneBotEventMapper(objectMapper, bots, entityIds, messages, messageCodec)

    @Bean(destroyMethod = "close")
    fun oneBotActionService(
        objectMapper: ObjectMapper,
        clients: BotOpenApiClientProvider,
        bots: BotRepository,
        supervisor: BotSupervisor,
        entityIds: OneBotEntityIdRepository,
        messages: OneBotMessageRepository,
        messageCodec: OneBotMessageCodec,
        mediaCache: OneBotMediaCache,
        eventMapper: OneBotEventMapper,
    ) = OneBotActionService(
        objectMapper,
        clients,
        bots,
        supervisor,
        entityIds,
        messages,
        messageCodec,
        mediaCache,
        eventMapper,
    )

    @Bean
    fun oneBotRuntimeManager(
        objectMapper: ObjectMapper,
        configurations: OneBot11ConfigurationService,
        gatewayEvents: BotGatewayEventSource,
        supervisor: BotSupervisor,
        eventMapper: OneBotEventMapper,
        actions: OneBotActionService,
    ) = OneBotRuntimeManager(
        objectMapper,
        configurations,
        gatewayEvents,
        supervisor,
        eventMapper,
        actions,
    )

    @Bean
    fun oneBot11Lifecycle(runtimes: OneBotRuntimeManager): FrameworkModuleLifecycle =
        object : FrameworkModuleLifecycle {
            override val moduleId: String = "onebot11"

            override fun start(context: ModuleContext) {
                runtimes.start()
            }

            override fun stop() {
                runtimes.close()
            }
        }

    @Bean
    fun oneBot11DatabaseTransition(runtimes: OneBotRuntimeManager): DatabaseTransitionParticipant =
        object : DatabaseTransitionParticipant {
            override fun beforeOrder(): Int = 275

            override fun afterOrder(): Int = 275

            override fun beforeDatabaseChange() {
                runtimes.beforeDatabaseChange()
            }

            override fun afterDatabaseChange() {
                runtimes.afterDatabaseChange()
            }
        }
}
