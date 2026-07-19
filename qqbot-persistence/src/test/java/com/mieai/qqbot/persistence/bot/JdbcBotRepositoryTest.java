package com.mieai.qqbot.persistence.bot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mieai.qqbot.domain.bot.BotDefinition;
import com.mieai.qqbot.domain.bot.BotEnvironment;
import com.mieai.qqbot.domain.bot.BotId;
import com.mieai.qqbot.domain.bot.BotRevision;
import com.mieai.qqbot.domain.bot.GatewayIntents;
import com.mieai.qqbot.domain.bot.QqAppId;
import com.mieai.qqbot.domain.bot.ShardSpec;
import com.mieai.qqbot.persistence.sqlite.SQLiteDataSourceFactory;
import com.mieai.qqbot.persistence.sqlite.SQLiteDatabaseInitializer;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;

class JdbcBotRepositoryTest {
    private static final Instant CREATED_AT = Instant.parse("2026-07-16T12:00:00Z");

    @TempDir
    private Path temporaryDirectory;

    private DataSource dataSource;
    private BotRepository repository;

    @BeforeEach
    void setUp() {
        dataSource = SQLiteDataSourceFactory.create(temporaryDirectory.resolve("repository.db"));
        SQLiteDatabaseInitializer.migrate(dataSource);
        repository = new JdbcBotRepository(dataSource);
    }

    @Test
    void insertsAndFindsACompleteStoredBot() throws SQLException {
        StoredBot bot = bot("550e8400-e29b-41d4-a716-446655440000", "10001", true);

        repository.insert(bot);

        assertThat(repository.findById(bot.id())).contains(bot);
        assertThat(repository.findById(BotId.parse("550e8400-e29b-41d4-a716-446655440099"))).isEmpty();
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(
                        "SELECT created_at, updated_at FROM bots WHERE id = '" + bot.id() + "'")) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getString("created_at")).endsWith("Z");
            assertThat(resultSet.getString("updated_at")).endsWith("Z");
        }
    }

    @Test
    void findsAllBotsIncludingDisabledInStableOrder() {
        StoredBot laterEnabled = bot("550e8400-e29b-41d4-a716-446655440002", "10002", true);
        StoredBot disabled = bot("550e8400-e29b-41d4-a716-446655440003", "10003", false);
        StoredBot earlierEnabled = bot("550e8400-e29b-41d4-a716-446655440001", "10001", true);
        repository.insert(laterEnabled);
        repository.insert(disabled);
        repository.insert(earlierEnabled);

        List<StoredBot> all = repository.findAll();

        assertThat(all).containsExactly(earlierEnabled, laterEnabled, disabled);
        assertThatThrownBy(() -> all.add(disabled)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void findsOnlyEnabledBotsInStableOrder() {
        StoredBot laterEnabled = bot("550e8400-e29b-41d4-a716-446655440002", "10002", true);
        StoredBot disabled = bot("550e8400-e29b-41d4-a716-446655440003", "10003", false);
        StoredBot earlierEnabled = bot("550e8400-e29b-41d4-a716-446655440001", "10001", true);
        repository.insert(laterEnabled);
        repository.insert(disabled);
        repository.insert(earlierEnabled);

        List<StoredBot> enabled = repository.findEnabled();

        assertThat(enabled).containsExactly(earlierEnabled, laterEnabled);
        assertThatThrownBy(() -> enabled.add(disabled)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void enforcesEnvironmentAndAppIdUniqueness() {
        repository.insert(bot("550e8400-e29b-41d4-a716-446655440001", "10001", true));

        assertThatThrownBy(() -> repository.insert(
                        bot("550e8400-e29b-41d4-a716-446655440002", "10001", false)))
                .isInstanceOf(DataAccessException.class);

        StoredBot sameAppIdInProduction = new StoredBot(
                definition("550e8400-e29b-41d4-a716-446655440003", "10001", true,
                        BotEnvironment.PRODUCTION, BotRevision.initial(), CREATED_AT),
                SecretCiphertext.of("v1:ciphertext-10001-production", "master-key-v1"));
        repository.insert(sameAppIdInProduction);
        assertThat(repository.findById(sameAppIdInProduction.id())).contains(sameAppIdInProduction);
    }

    @Test
    void updatesWithAnExpectedRevisionAndRejectsAStaleWriter() {
        StoredBot original = bot("550e8400-e29b-41d4-a716-446655440001", "10001", true);
        repository.insert(original);
        StoredBot desired = new StoredBot(
                new BotDefinition(
                        original.id(),
                        "Renamed Bot",
                        original.definition().appId(),
                        original.definition().environment(),
                        GatewayIntents.of(1L << 26),
                        new ShardSpec(1, 2),
                        false,
                        BotRevision.initial(),
                        CREATED_AT,
                        CREATED_AT.plusSeconds(30)),
                SecretCiphertext.of("v2:rotated-ciphertext", "master-key-v2"));

        BotRevision updatedRevision = repository.update(desired, BotRevision.initial());

        assertThat(updatedRevision).isEqualTo(BotRevision.of(2));
        StoredBot stored = repository.findById(original.id()).orElseThrow();
        assertThat(stored.definition().displayName()).isEqualTo("Renamed Bot");
        assertThat(stored.definition().revision()).isEqualTo(BotRevision.of(2));
        assertThat(stored.definition().enabled()).isFalse();
        assertThat(stored.definition().shardSpec()).isEqualTo(new ShardSpec(1, 2));
        assertThat(stored.appSecret()).isEqualTo(desired.appSecret());

        assertThatThrownBy(() -> repository.update(desired, BotRevision.initial()))
                .isInstanceOfSatisfying(OptimisticLockException.class, exception -> {
                    assertThat(exception.botId()).isEqualTo(original.id());
                    assertThat(exception.expectedRevision()).isEqualTo(BotRevision.initial());
                });
        assertThat(repository.findById(original.id()).orElseThrow().definition().revision())
                .isEqualTo(BotRevision.of(2));
    }

    @Test
    void validatesCandidateRevisionBeforeUpdating() {
        StoredBot original = bot("550e8400-e29b-41d4-a716-446655440001", "10001", true);
        repository.insert(original);
        StoredBot inconsistent = new StoredBot(
                definition(original.id().toString(), "10001", true,
                        BotEnvironment.SANDBOX, BotRevision.of(2), CREATED_AT),
                original.appSecret());

        assertThatIllegalArgumentException()
                .isThrownBy(() -> repository.update(inconsistent, BotRevision.initial()))
                .withMessageContaining("revision");
    }

    @Test
    void reportsMissingBotAsOptimisticLockFailure() {
        StoredBot missing = bot("550e8400-e29b-41d4-a716-446655440001", "10001", true);

        assertThatThrownBy(() -> repository.update(missing, BotRevision.initial()))
                .isInstanceOf(OptimisticLockException.class);
    }

    @Test
    void deletesBotOwnedInboxAndOutboxHistoryTransactionally() throws SQLException {
        StoredBot stored = bot("550e8400-e29b-41d4-a716-446655440001", "10001", true);
        repository.insert(stored);
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO event_inbox (id, environment, bot_id, event_type, platform_event_id,
                        payload, status, attempt, available_at, fencing_token, received_at, updated_at)
                    VALUES ('660e8400-e29b-41d4-a716-446655440001','SANDBOX',
                        '550e8400-e29b-41d4-a716-446655440001','MESSAGE_CREATE','event-1','{}',
                        'RECEIVED',0,'2026-07-16T12:00:00Z',0,'2026-07-16T12:00:00Z','2026-07-16T12:00:00Z')
                    """);
            statement.executeUpdate("""
                    INSERT INTO outbox_jobs (id, environment, bot_id, source_event_id, job_type,
                        payload, status, attempt, available_at, fencing_token, created_at, updated_at)
                    VALUES ('770e8400-e29b-41d4-a716-446655440001','SANDBOX',
                        '550e8400-e29b-41d4-a716-446655440001','660e8400-e29b-41d4-a716-446655440001',
                        'TEST','{}','PENDING',0,'2026-07-16T12:00:00Z',0,
                        '2026-07-16T12:00:00Z','2026-07-16T12:00:00Z')
                    """);
        }

        assertThat(repository.delete(stored.id())).isTrue();
        assertThat(repository.findById(stored.id())).isEmpty();
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            assertThat(queryCount(statement, "event_inbox")).isZero();
            assertThat(queryCount(statement, "outbox_jobs")).isZero();
        }
    }

    private static int queryCount(Statement statement, String table) throws SQLException {
        try (ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return result.next() ? result.getInt(1) : -1;
        }
    }

    private static StoredBot bot(String id, String appId, boolean enabled) {
        return new StoredBot(
                definition(id, appId, enabled, BotEnvironment.SANDBOX, BotRevision.initial(), CREATED_AT),
                SecretCiphertext.of("v1:ciphertext-" + appId, "master-key-v1"));
    }

    private static BotDefinition definition(
            String id,
            String appId,
            boolean enabled,
            BotEnvironment environment,
            BotRevision revision,
            Instant createdAt) {
        return new BotDefinition(
                BotId.of(UUID.fromString(id)),
                "Bot " + appId,
                QqAppId.of(appId),
                environment,
                GatewayIntents.of(512L),
                ShardSpec.single(),
                enabled,
                revision,
                createdAt,
                createdAt.plusSeconds(5));
    }
}
