package com.mieai.qqbot.modules.runtime;

import com.mieai.qqbot.modules.database.DatabaseTransitionParticipant;
import com.mieai.qqbot.runtime.outbox.ProductionOutboxWorker;
import com.mieai.qqbot.runtime.openapi.BotOpenApiClientProvider;
import com.mieai.qqbot.runtime.supervisor.BotSupervisor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;

@AutoConfiguration
@ConditionalOnProperty(name = "qqbot.modules.available.qqbot-runtime", havingValue = "true")
@ComponentScan(basePackages = {
        "com.mieai.qqbot.admin.bot",
        "com.mieai.qqbot.app.config",
        "com.mieai.qqbot.app.gateway",
        "com.mieai.qqbot.app.media",
        "com.mieai.qqbot.app.outbox"
})
public class QqBotRuntimeModuleConfiguration {
    @Bean
    DatabaseTransitionParticipant botSupervisorDatabaseTransition(BotSupervisor supervisor) {
        return new DatabaseTransitionParticipant() {
            @Override public int beforeOrder() { return 300; }
            @Override public int afterOrder() { return 100; }
            @Override public void beforeDatabaseChange() { supervisor.beforeActiveDatabaseChange(); }
            @Override public void afterDatabaseChange() { supervisor.activeDatabaseChanged(); }
        };
    }

    @Bean
    DatabaseTransitionParticipant outboxDatabaseTransition(ProductionOutboxWorker worker) {
        return new DatabaseTransitionParticipant() {
            @Override public int beforeOrder() { return 200; }
            @Override public int afterOrder() { return 300; }
            @Override public void beforeDatabaseChange() { worker.beforeActiveDatabaseChange(); }
            @Override public void afterDatabaseChange() { worker.activeDatabaseChanged(); }
        };
    }

    @Bean
    DatabaseTransitionParticipant openApiClientDatabaseTransition(
            BotOpenApiClientProvider clients) {
        return new DatabaseTransitionParticipant() {
            @Override public int beforeOrder() { return 250; }
            @Override public int afterOrder() { return 250; }
            @Override public void beforeDatabaseChange() { clients.invalidateAll(); }
            @Override public void afterDatabaseChange() {}
        };
    }
}
