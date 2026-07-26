package com.mieai.qqbot.modules.runtime

import com.mieai.qqbot.modules.database.DatabaseTransitionParticipant
import com.mieai.qqbot.runtime.openapi.BotOpenApiClientProvider
import com.mieai.qqbot.runtime.outbox.ProductionOutboxWorker
import com.mieai.qqbot.runtime.supervisor.BotSupervisor
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan

@AutoConfiguration
@ConditionalOnProperty(name = ["qqbot.modules.available.qqbot-runtime"], havingValue = "true")
@ComponentScan(
    basePackages = [
        "com.mieai.qqbot.admin.bot",
        "com.mieai.qqbot.app.config",
        "com.mieai.qqbot.app.gateway",
        "com.mieai.qqbot.app.media",
        "com.mieai.qqbot.app.outbox",
    ],
)
class QqBotRuntimeModuleConfiguration {
    @Bean
    fun botSupervisorDatabaseTransition(supervisor: BotSupervisor): DatabaseTransitionParticipant =
        object : DatabaseTransitionParticipant {
            override fun beforeOrder(): Int = 300
            override fun afterOrder(): Int = 100
            override fun beforeDatabaseChange() = supervisor.beforeActiveDatabaseChange()
            override fun afterDatabaseChange() {
                supervisor.activeDatabaseChanged()
            }
        }

    @Bean
    fun outboxDatabaseTransition(worker: ProductionOutboxWorker): DatabaseTransitionParticipant =
        object : DatabaseTransitionParticipant {
            override fun beforeOrder(): Int = 200
            override fun afterOrder(): Int = 300
            override fun beforeDatabaseChange() = worker.beforeActiveDatabaseChange()
            override fun afterDatabaseChange() = worker.activeDatabaseChanged()
        }

    @Bean
    fun openApiClientDatabaseTransition(
        clients: BotOpenApiClientProvider,
    ): DatabaseTransitionParticipant = object : DatabaseTransitionParticipant {
        override fun beforeOrder(): Int = 250
        override fun afterOrder(): Int = 250
        override fun beforeDatabaseChange() = clients.invalidateAll()
        override fun afterDatabaseChange() = Unit
    }
}
