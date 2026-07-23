package com.mieai.qqbot.app.database;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mieai.qqbot.runtime.security.AesGcmConfigurationSecretCipher;
import com.mieai.qqbot.runtime.security.KeyProvider;
import com.mieai.qqbot.modules.database.DatabaseTransitionParticipant;
import com.mieai.qqbot.module.host.ModuleArtifactRegistry;
import java.util.Comparator;
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
    ModuleDatabaseMigrator moduleDatabaseMigrator(ModuleArtifactRegistry artifacts) {
        return new ModuleDatabaseMigrator(artifacts);
    }

    @Bean
    DatabaseCandidateFactory databaseCandidateFactory(ModuleDatabaseMigrator moduleMigrator) {
        return new DatabaseCandidateFactory(moduleMigrator);
    }

    @Bean(destroyMethod = "close")
    DatabaseRuntime databaseRuntime(
            DatabaseBootstrapProperties properties,
            DatabaseCandidateFactory candidateFactory,
            DatabaseConfigurationStore configurationStore,
            ObjectProvider<DatabaseTransitionParticipant> transitionParticipants) {
        return new DatabaseRuntime(
                properties,
                candidateFactory,
                configurationStore,
                java.time.Clock.systemUTC(),
                () -> beforeDatabaseChange(transitionParticipants),
                () -> afterDatabaseChange(transitionParticipants));
    }

    @Bean
    @Primary
    DataSource dataSource(DatabaseRuntime databaseRuntime) {
        return databaseRuntime.dataSource();
    }

    private static void beforeDatabaseChange(
            ObjectProvider<DatabaseTransitionParticipant> participants) {
        try {
            participants.orderedStream()
                    .sorted(Comparator.comparingInt(DatabaseTransitionParticipant::beforeOrder))
                    .forEach(DatabaseTransitionParticipant::beforeDatabaseChange);
        } catch (RuntimeException failure) {
            try {
                afterDatabaseChange(participants);
            } catch (RuntimeException recoveryFailure) {
                failure.addSuppressed(recoveryFailure);
            }
            throw failure;
        }
    }

    private static void afterDatabaseChange(
            ObjectProvider<DatabaseTransitionParticipant> participants) {
        RuntimeException[] failure = new RuntimeException[1];
        participants.orderedStream()
                .sorted(Comparator.comparingInt(DatabaseTransitionParticipant::afterOrder))
                .forEach(participant -> runAll(failure, participant::afterDatabaseChange));
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
