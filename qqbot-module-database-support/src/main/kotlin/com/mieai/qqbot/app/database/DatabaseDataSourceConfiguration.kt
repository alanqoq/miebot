package com.mieai.qqbot.app.database

import com.fasterxml.jackson.databind.ObjectMapper
import com.mieai.qqbot.module.host.ModuleArtifactRegistry
import com.mieai.qqbot.modules.database.DatabaseTransitionParticipant
import com.mieai.qqbot.runtime.security.AesGcmConfigurationSecretCipher
import com.mieai.qqbot.runtime.security.KeyProvider
import javax.sql.DataSource
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DatabaseBootstrapProperties::class)
class DatabaseDataSourceConfiguration {
    @Bean
    fun databaseConfigurationStore(
        properties: DatabaseBootstrapProperties,
        objectMapper: ObjectMapper,
        keyProvider: KeyProvider,
    ): DatabaseConfigurationStore = DatabaseConfigurationStore(
        properties.configFile,
        properties.candidateConfigFile,
        objectMapper,
        AesGcmConfigurationSecretCipher(keyProvider),
    )

    @Bean
    fun moduleDatabaseMigrator(artifacts: ModuleArtifactRegistry): ModuleDatabaseMigrator =
        ModuleDatabaseMigrator(artifacts)

    @Bean
    fun databaseCandidateFactory(moduleMigrator: ModuleDatabaseMigrator): DatabaseCandidateFactory =
        DatabaseCandidateFactory(moduleMigrator)

    @Bean(destroyMethod = "close")
    fun databaseRuntime(
        properties: DatabaseBootstrapProperties,
        candidateFactory: DatabaseCandidateFactory,
        configurationStore: DatabaseConfigurationStore,
        transitionParticipants: ObjectProvider<DatabaseTransitionParticipant>,
    ): DatabaseRuntime = DatabaseRuntime(
        properties,
        candidateFactory,
        configurationStore,
        java.time.Clock.systemUTC(),
        { beforeDatabaseChange(transitionParticipants) },
        { afterDatabaseChange(transitionParticipants) },
    )

    @Bean
    @Primary
    fun dataSource(databaseRuntime: DatabaseRuntime): DataSource = databaseRuntime.activeDataSource

    private companion object {
        fun beforeDatabaseChange(participants: ObjectProvider<DatabaseTransitionParticipant>) {
            try {
                participants.orderedStream()
                    .sorted(compareBy(DatabaseTransitionParticipant::beforeOrder))
                    .forEach(DatabaseTransitionParticipant::beforeDatabaseChange)
            } catch (failure: RuntimeException) {
                try {
                    afterDatabaseChange(participants)
                } catch (recoveryFailure: RuntimeException) {
                    failure.addSuppressed(recoveryFailure)
                }
                throw failure
            }
        }

        fun afterDatabaseChange(participants: ObjectProvider<DatabaseTransitionParticipant>) {
            var failure: RuntimeException? = null
            participants.orderedStream()
                .sorted(compareBy(DatabaseTransitionParticipant::afterOrder))
                .forEach { participant ->
                    failure = runAll(failure, participant::afterDatabaseChange)
                }
            failure?.let { throw it }
        }

        fun runAll(firstFailure: RuntimeException?, operation: () -> Unit): RuntimeException? = try {
            operation()
            firstFailure
        } catch (exception: RuntimeException) {
            if (firstFailure == null) exception else firstFailure.apply { addSuppressed(exception) }
        }
    }
}
