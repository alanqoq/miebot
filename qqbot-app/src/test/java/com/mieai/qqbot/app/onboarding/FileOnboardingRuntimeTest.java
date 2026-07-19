package com.mieai.qqbot.app.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.mieai.qqbot.admin.database.DatabaseAdministrationService;
import com.mieai.qqbot.admin.database.DatabaseConfigurationView;
import com.mieai.qqbot.admin.database.DatabaseSslMode;
import com.mieai.qqbot.admin.database.DatabaseType;
import com.mieai.qqbot.admin.onboarding.OnboardingOperationException;
import com.mieai.qqbot.admin.onboarding.OnboardingStage;
import com.mieai.qqbot.admin.onboarding.OnboardingStatusResponse;
import com.mieai.qqbot.persistence.admin.AdminUserRepository;
import com.mieai.qqbot.persistence.bot.BotRepository;
import com.mieai.qqbot.persistence.bot.StoredBot;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileOnboardingRuntimeTest {
    private static final Instant NOW = Instant.parse("2026-07-17T12:34:56Z");
    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder().findAndAddModules().build();

    @TempDir
    private Path temporaryDirectory;

    private final AtomicBoolean adminConfigured = new AtomicBoolean();
    private AdminUserRepository adminRepository;
    private BotRepository botRepository;
    private DatabaseAdministrationService databaseService;
    private DatabaseConfigurationView activeDatabase;
    private List<StoredBot> bots;
    private Path stateFile;

    @BeforeEach
    void setUp() {
        adminRepository = mock(AdminUserRepository.class);
        when(adminRepository.exists()).thenAnswer(invocation -> adminConfigured.get());
        botRepository = mock(BotRepository.class);
        bots = List.of();
        when(botRepository.findAll()).thenAnswer(invocation -> bots);
        databaseService = mock(DatabaseAdministrationService.class);
        activeDatabase = configuration(1L, DatabaseType.SQLITE);
        when(databaseService.current()).thenAnswer(invocation -> activeDatabase);
        stateFile = temporaryDirectory.resolve("config").resolve("onboarding.json");
    }

    @Test
    void freshInstallPersistsAdminThenDatabaseStageAndRecoversAcrossRestart() {
        FileOnboardingRuntime runtime = runtime();

        assertStatus(runtime.status(), OnboardingStage.ADMIN, null, 0L, null);
        assertThat(stateFile).isRegularFile();

        adminConfigured.set(true);
        runtime.administratorConfigured();
        assertStatus(runtime.status(), OnboardingStage.DATABASE, null, 0L, null);

        FileOnboardingRuntime restarted = runtime();
        assertStatus(restarted.status(), OnboardingStage.DATABASE, null, 0L, null);
    }

    @Test
    void administratorInsertWithoutCallbackRepairsAdminStageOnRestart() {
        FileOnboardingRuntime first = runtime();
        assertThat(first.status().stage()).isEqualTo(OnboardingStage.ADMIN);

        adminConfigured.set(true);
        FileOnboardingRuntime restarted = runtime();

        assertThat(restarted.status().stage()).isEqualTo(OnboardingStage.DATABASE);
    }

    @Test
    void databaseStepRequiresMatchingActiveRevisionAndTypeThenIsIdempotent() {
        adminConfigured.set(true);
        new OnboardingStateStore(stateFile, OBJECT_MAPPER).save(OnboardingFileState.database());
        FileOnboardingRuntime runtime = runtime();

        assertThatThrownBy(() -> runtime.databaseConfigured(2L, DatabaseType.SQLITE))
                .isInstanceOfSatisfying(OnboardingOperationException.class,
                        failure -> assertThat(failure.code()).isEqualTo("DATABASE_CONFIG_CONFLICT"));
        assertThatThrownBy(() -> runtime.databaseConfigured(1L, DatabaseType.MYSQL))
                .isInstanceOfSatisfying(OnboardingOperationException.class,
                        failure -> assertThat(failure.code()).isEqualTo("DATABASE_CONFIG_CONFLICT"));

        OnboardingStatusResponse configured = runtime.databaseConfigured(1L, DatabaseType.SQLITE);
        assertStatus(configured, OnboardingStage.BOT, DatabaseType.SQLITE, 0L, null);
        assertThat(runtime.databaseConfigured(1L, DatabaseType.SQLITE)).isEqualTo(configured);

        assertStatus(runtime().status(), OnboardingStage.BOT, DatabaseType.SQLITE, 0L, null);
    }

    @Test
    void rejectsChangingTheSelectedDatabaseAfterEnteringBotStage() {
        adminConfigured.set(true);
        new OnboardingStateStore(stateFile, OBJECT_MAPPER).save(
                OnboardingFileState.bot(DatabaseType.SQLITE));
        activeDatabase = configuration(8L, DatabaseType.MYSQL);
        FileOnboardingRuntime runtime = runtime();

        assertThatThrownBy(() -> runtime.databaseConfigured(8L, DatabaseType.MYSQL))
                .isInstanceOfSatisfying(OnboardingOperationException.class,
                        failure -> assertThat(failure.code()).isEqualTo("ONBOARDING_STATE_CONFLICT"));
        assertThat(runtime.status().databaseType()).isEqualTo(DatabaseType.SQLITE);
    }

    @Test
    void completionRequiresAtLeastOneBotAndPersistsCompletionTime() {
        adminConfigured.set(true);
        new OnboardingStateStore(stateFile, OBJECT_MAPPER).save(
                OnboardingFileState.bot(DatabaseType.POSTGRESQL));
        activeDatabase = configuration(7L, DatabaseType.POSTGRESQL);
        FileOnboardingRuntime runtime = runtime();

        assertThatThrownBy(runtime::complete)
                .isInstanceOfSatisfying(OnboardingOperationException.class,
                        failure -> assertThat(failure.code()).isEqualTo("ONBOARDING_BOT_REQUIRED"));

        bots = List.of(mock(StoredBot.class), mock(StoredBot.class));
        OnboardingStatusResponse completed = runtime.complete();
        assertStatus(completed, OnboardingStage.COMPLETE, DatabaseType.POSTGRESQL, 2L, NOW);
        assertThat(runtime.complete()).isEqualTo(completed);
        assertStatus(runtime().status(), OnboardingStage.COMPLETE, DatabaseType.POSTGRESQL, 2L, NOW);
    }

    @Test
    void rejectsCompletionBeforeDatabaseStageIsConfirmed() {
        adminConfigured.set(true);
        new OnboardingStateStore(stateFile, OBJECT_MAPPER).save(OnboardingFileState.database());
        FileOnboardingRuntime runtime = runtime();

        assertThatThrownBy(runtime::complete)
                .isInstanceOfSatisfying(OnboardingOperationException.class,
                        failure -> assertThat(failure.code()).isEqualTo("ONBOARDING_DATABASE_REQUIRED"));
        assertThat(runtime.status().stage()).isEqualTo(OnboardingStage.DATABASE);
    }

    @Test
    void missingStateForExistingInstallationBootstrapsLegacyComplete() {
        adminConfigured.set(true);
        activeDatabase = configuration(11L, DatabaseType.MYSQL);

        FileOnboardingRuntime runtime = runtime();

        assertStatus(runtime.status(), OnboardingStage.COMPLETE, DatabaseType.MYSQL, 0L, NOW);
        assertThat(stateFile).isRegularFile();
    }

    @Test
    void completedStateSurvivesAHotSwitchToAnEmptyDatabase() {
        adminConfigured.set(true);
        new OnboardingStateStore(stateFile, OBJECT_MAPPER).save(
                OnboardingFileState.complete(DatabaseType.SQLITE, NOW));
        activeDatabase = configuration(9L, DatabaseType.POSTGRESQL);

        OnboardingStatusResponse status = runtime().status();

        assertStatus(status, OnboardingStage.COMPLETE, DatabaseType.SQLITE, 0L, NOW);
    }

    @Test
    void malformedStateFailsClosedInsteadOfRestartingOnboarding() throws Exception {
        Files.createDirectories(stateFile.getParent());
        Files.writeString(stateFile, "{not-json");

        assertThatThrownBy(this::runtime)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Unable to read onboarding state file");
    }

    private FileOnboardingRuntime runtime() {
        return new FileOnboardingRuntime(
                stateFile,
                OBJECT_MAPPER,
                adminRepository,
                botRepository,
                databaseService,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static DatabaseConfigurationView configuration(long revision, DatabaseType type) {
        return new DatabaseConfigurationView(
                revision,
                type,
                type == DatabaseType.SQLITE ? "qqbot.db" : null,
                type == DatabaseType.SQLITE ? 5_000L : null,
                type == DatabaseType.SQLITE ? null : "database.internal",
                type == DatabaseType.SQLITE ? null : (type == DatabaseType.MYSQL ? 3306 : 5432),
                type == DatabaseType.SQLITE ? null : "qqbot",
                type == DatabaseType.SQLITE ? null : "qqbot",
                type == DatabaseType.SQLITE ? null : DatabaseSslMode.PREFERRED,
                type == DatabaseType.SQLITE ? null : 5_000L,
                type != DatabaseType.SQLITE,
                type == DatabaseType.SQLITE ? "SQLite" : type.name(),
                "test",
                false,
                null);
    }

    private static void assertStatus(
            OnboardingStatusResponse actual,
            OnboardingStage stage,
            DatabaseType databaseType,
            long botCount,
            Instant completedAt) {
        assertThat(actual.stage()).isEqualTo(stage);
        assertThat(actual.databaseType()).isEqualTo(databaseType);
        assertThat(actual.botCount()).isEqualTo(botCount);
        assertThat(actual.completedAt()).isEqualTo(completedAt);
    }
}
