package com.mieai.qqbot.app.onboarding

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.json.JsonMapper
import com.mieai.qqbot.admin.database.DatabaseAdministrationService
import com.mieai.qqbot.admin.database.DatabaseConfigurationView
import com.mieai.qqbot.admin.database.DatabaseSslMode
import com.mieai.qqbot.admin.database.DatabaseType
import com.mieai.qqbot.admin.onboarding.OnboardingOperationException
import com.mieai.qqbot.admin.onboarding.OnboardingStage
import com.mieai.qqbot.admin.onboarding.OnboardingStatusResponse
import com.mieai.qqbot.persistence.admin.AdminUserRepository
import com.mieai.qqbot.persistence.bot.BotRepository
import com.mieai.qqbot.persistence.bot.StoredBot
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicBoolean

class FileOnboardingRuntimeTest {
    @TempDir lateinit var temporaryDirectory: Path
    private val adminConfigured = AtomicBoolean()
    private lateinit var adminRepository: AdminUserRepository
    private lateinit var botRepository: BotRepository
    private lateinit var databaseService: DatabaseAdministrationService
    private lateinit var activeDatabase: DatabaseConfigurationView
    private var bots: List<StoredBot> = emptyList()
    private lateinit var stateFile: Path

    @BeforeEach fun setUp() {
        adminRepository = mock(AdminUserRepository::class.java); `when`(adminRepository.exists()).thenAnswer { adminConfigured.get() }
        botRepository = mock(BotRepository::class.java); bots = emptyList(); `when`(botRepository.findAll()).thenAnswer { bots }
        databaseService = mock(DatabaseAdministrationService::class.java); activeDatabase = configuration(1, DatabaseType.SQLITE); `when`(databaseService.current()).thenAnswer { activeDatabase }
        stateFile = temporaryDirectory.resolve("config").resolve("onboarding.json")
    }

    @Test fun freshInstallPersistsAdminThenDatabaseStageAndRecoversAcrossRestart() {
        val runtime = runtime(); assertStatus(runtime.status(), OnboardingStage.ADMIN, null, 0, null); assertThat(stateFile).isRegularFile()
        adminConfigured.set(true); runtime.administratorConfigured(); assertStatus(runtime.status(), OnboardingStage.DATABASE, null, 0, null); assertStatus(runtime().status(), OnboardingStage.DATABASE, null, 0, null)
    }
    @Test fun administratorInsertWithoutCallbackRepairsAdminStageOnRestart() { val first = runtime(); assertThat(first.status().stage).isEqualTo(OnboardingStage.ADMIN); adminConfigured.set(true); assertThat(runtime().status().stage).isEqualTo(OnboardingStage.DATABASE) }
    @Test fun databaseStepRequiresMatchingActiveRevisionAndTypeThenIsIdempotent() {
        adminConfigured.set(true); OnboardingStateStore(stateFile, OBJECT_MAPPER).save(OnboardingFileState.database()); val runtime = runtime()
        assertThatThrownBy { runtime.databaseConfigured(2, DatabaseType.SQLITE) }.isInstanceOfSatisfying(OnboardingOperationException::class.java) { assertThat(it.code).isEqualTo("DATABASE_CONFIG_CONFLICT") }
        assertThatThrownBy { runtime.databaseConfigured(1, DatabaseType.MYSQL) }.isInstanceOfSatisfying(OnboardingOperationException::class.java) { assertThat(it.code).isEqualTo("DATABASE_CONFIG_CONFLICT") }
        val configured = runtime.databaseConfigured(1, DatabaseType.SQLITE); assertStatus(configured, OnboardingStage.BOT, DatabaseType.SQLITE, 0, null); assertThat(runtime.databaseConfigured(1, DatabaseType.SQLITE)).isEqualTo(configured); assertStatus(runtime().status(), OnboardingStage.BOT, DatabaseType.SQLITE, 0, null)
    }
    @Test fun rejectsChangingTheSelectedDatabaseAfterEnteringBotStage() {
        adminConfigured.set(true); OnboardingStateStore(stateFile, OBJECT_MAPPER).save(OnboardingFileState.bot(DatabaseType.SQLITE)); activeDatabase = configuration(8, DatabaseType.MYSQL); val runtime = runtime()
        assertThatThrownBy { runtime.databaseConfigured(8, DatabaseType.MYSQL) }.isInstanceOfSatisfying(OnboardingOperationException::class.java) { assertThat(it.code).isEqualTo("ONBOARDING_STATE_CONFLICT") }; assertThat(runtime.status().databaseType).isEqualTo(DatabaseType.SQLITE)
    }
    @Test fun completionRequiresAtLeastOneBotAndPersistsCompletionTime() {
        adminConfigured.set(true); OnboardingStateStore(stateFile, OBJECT_MAPPER).save(OnboardingFileState.bot(DatabaseType.POSTGRESQL)); activeDatabase = configuration(7, DatabaseType.POSTGRESQL); val runtime = runtime()
        assertThatThrownBy { runtime.complete() }.isInstanceOfSatisfying(OnboardingOperationException::class.java) { assertThat(it.code).isEqualTo("ONBOARDING_BOT_REQUIRED") }
        bots = listOf(mock(StoredBot::class.java), mock(StoredBot::class.java)); val completed = runtime.complete(); assertStatus(completed, OnboardingStage.COMPLETE, DatabaseType.POSTGRESQL, 2, NOW); assertThat(runtime.complete()).isEqualTo(completed); assertStatus(runtime().status(), OnboardingStage.COMPLETE, DatabaseType.POSTGRESQL, 2, NOW)
    }
    @Test fun rejectsCompletionBeforeDatabaseStageIsConfirmed() { adminConfigured.set(true); OnboardingStateStore(stateFile, OBJECT_MAPPER).save(OnboardingFileState.database()); val runtime = runtime(); assertThatThrownBy { runtime.complete() }.isInstanceOfSatisfying(OnboardingOperationException::class.java) { assertThat(it.code).isEqualTo("ONBOARDING_DATABASE_REQUIRED") }; assertThat(runtime.status().stage).isEqualTo(OnboardingStage.DATABASE) }
    @Test fun missingStateForExistingInstallationBootstrapsLegacyComplete() { adminConfigured.set(true); activeDatabase = configuration(11, DatabaseType.MYSQL); val runtime = runtime(); assertStatus(runtime.status(), OnboardingStage.COMPLETE, DatabaseType.MYSQL, 0, NOW); assertThat(stateFile).isRegularFile() }
    @Test fun completedStateSurvivesAHotSwitchToAnEmptyDatabase() { adminConfigured.set(true); OnboardingStateStore(stateFile, OBJECT_MAPPER).save(OnboardingFileState.complete(DatabaseType.SQLITE, NOW)); activeDatabase = configuration(9, DatabaseType.POSTGRESQL); assertStatus(runtime().status(), OnboardingStage.COMPLETE, DatabaseType.SQLITE, 0, NOW) }
    @Test fun malformedStateFailsClosedInsteadOfRestartingOnboarding() { Files.createDirectories(stateFile.parent); Files.writeString(stateFile, "{not-json"); assertThatThrownBy { runtime() }.isInstanceOf(IllegalStateException::class.java).hasMessage("Unable to read onboarding state file") }

    private fun runtime() = FileOnboardingRuntime(stateFile, OBJECT_MAPPER, adminRepository, botRepository, databaseService, Clock.fixed(NOW, ZoneOffset.UTC))
    private fun configuration(revision: Long, type: DatabaseType) = DatabaseConfigurationView(revision, type, if (type == DatabaseType.SQLITE) "qqbot.db" else null, if (type == DatabaseType.SQLITE) 5_000 else null, if (type == DatabaseType.SQLITE) null else "database.internal", if (type == DatabaseType.SQLITE) null else if (type == DatabaseType.MYSQL) 3306 else 5432, if (type == DatabaseType.SQLITE) null else "qqbot", if (type == DatabaseType.SQLITE) null else "qqbot", if (type == DatabaseType.SQLITE) null else DatabaseSslMode.PREFERRED, if (type == DatabaseType.SQLITE) null else 5_000, type != DatabaseType.SQLITE, if (type == DatabaseType.SQLITE) "SQLite" else type.name, "test", false, null)
    private fun assertStatus(actual: OnboardingStatusResponse, stage: OnboardingStage, databaseType: DatabaseType?, botCount: Long, completedAt: Instant?) { assertThat(actual.stage).isEqualTo(stage); assertThat(actual.databaseType).isEqualTo(databaseType); assertThat(actual.botCount).isEqualTo(botCount); assertThat(actual.completedAt).isEqualTo(completedAt) }
    companion object { private val NOW = Instant.parse("2026-07-17T12:34:56Z"); private val OBJECT_MAPPER: ObjectMapper = JsonMapper.builder().findAndAddModules().build() }
}
