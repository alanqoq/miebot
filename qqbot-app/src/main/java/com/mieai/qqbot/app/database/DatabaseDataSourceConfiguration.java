package com.mieai.qqbot.app.database;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mieai.qqbot.runtime.security.AesGcmConfigurationSecretCipher;
import com.mieai.qqbot.runtime.security.KeyProvider;
import com.mieai.qqbot.runtime.supervisor.BotSupervisor;
import com.mieai.qqbot.plugin.host.PluginRuntimeService;
import com.mieai.qqbot.runtime.outbox.ProductionOutboxWorker;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DatabaseBootstrapProperties.class)
public class DatabaseDataSourceConfiguration {

    @Bean
    DatabaseConfigurationStore databaseConfigurationStore(
            DatabaseBootstrapProperties properties,
            ObjectMapper objectMapper,
            KeyProvider keyProvider) {
        return new DatabaseConfigurationStore(
                properties.getConfigFile(),
                properties.getCandidateConfigFile(),
                objectMapper,
                new AesGcmConfigurationSecretCipher(keyProvider));
    }

    @Bean
    DatabaseCandidateFactory databaseCandidateFactory() {
        return new DatabaseCandidateFactory();
    }

    @Bean(destroyMethod = "close")
    DatabaseRuntime databaseRuntime(
            DatabaseBootstrapProperties properties,
            DatabaseCandidateFactory candidateFactory,
            DatabaseConfigurationStore configurationStore,
            ObjectProvider<BotSupervisor> botSupervisorProvider,
            ObjectProvider<PluginRuntimeService> pluginRuntimeProvider,
            ObjectProvider<ProductionOutboxWorker> outboxWorkerProvider) {
        return new DatabaseRuntime(
                properties,
                candidateFactory,
                configurationStore,
                java.time.Clock.systemUTC(),
                () -> beforeDatabaseChange(botSupervisorProvider, pluginRuntimeProvider, outboxWorkerProvider),
                () -> afterDatabaseChange(botSupervisorProvider, pluginRuntimeProvider, outboxWorkerProvider));
    }

    @Bean
    @Primary
    DataSource dataSource(DatabaseRuntime databaseRuntime) {
        return databaseRuntime.dataSource();
    }

    private static void beforeDatabaseChange(ObjectProvider<BotSupervisor> supervisors,
            ObjectProvider<PluginRuntimeService> plugins,
            ObjectProvider<ProductionOutboxWorker> outboxWorkers) {
        try {
            plugins.ifAvailable(PluginRuntimeService::beforeActiveDatabaseChange);
            outboxWorkers.ifAvailable(ProductionOutboxWorker::beforeActiveDatabaseChange);
            supervisors.ifAvailable(BotSupervisor::beforeActiveDatabaseChange);
        } catch (RuntimeException failure) {
            try {
                afterDatabaseChange(supervisors, plugins, outboxWorkers);
            } catch (RuntimeException recoveryFailure) {
                failure.addSuppressed(recoveryFailure);
            }
            throw failure;
        }
    }

    private static void afterDatabaseChange(ObjectProvider<BotSupervisor> supervisors,
            ObjectProvider<PluginRuntimeService> plugins,
            ObjectProvider<ProductionOutboxWorker> outboxWorkers) {
        RuntimeException[] failure = new RuntimeException[1];
        runAll(failure, () -> supervisors.ifAvailable(BotSupervisor::activeDatabaseChanged));
        runAll(failure, () -> plugins.ifAvailable(PluginRuntimeService::activeDatabaseChanged));
        runAll(failure, () -> outboxWorkers.ifAvailable(ProductionOutboxWorker::activeDatabaseChanged));
        if (failure[0] != null) throw failure[0];
    }

    private static void runAll(RuntimeException[] firstFailure, Runnable operation) {
        try {
            operation.run();
        } catch (RuntimeException exception) {
            if (firstFailure[0] == null) firstFailure[0] = exception;
            else firstFailure[0].addSuppressed(exception);
        }
    }

}
