package com.mieai.qqbot.persistence.bot;

import com.mieai.qqbot.domain.bot.BotDefinition;
import com.mieai.qqbot.domain.bot.BotId;
import com.mieai.qqbot.domain.bot.BotRevision;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Spring JDBC implementation that keeps SQL and row mapping private to persistence. */
public final class JdbcBotRepository implements BotRepository {
    private static final String SELECT_COLUMNS = """
            id, display_name, app_id, environment,
            app_secret_ciphertext, app_secret_key_id,
            intents, shard_index, shard_count, max_media_upload_bytes, enabled, revision,
            created_at, updated_at
            """;
    private static final StoredBotRowMapper ROW_MAPPER = new StoredBotRowMapper();

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public JdbcBotRepository(DataSource dataSource) {
        DataSource required = Objects.requireNonNull(dataSource, "dataSource must not be null");
        jdbc = new JdbcTemplate(required);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(required));
    }

    @Override
    public Optional<StoredBot> findById(BotId id) {
        Objects.requireNonNull(id, "id must not be null");
        List<StoredBot> matches = jdbc.query(
                "SELECT " + SELECT_COLUMNS + " FROM bots WHERE id = ?",
                ROW_MAPPER,
                id.toString());
        return matches.stream().findFirst();
    }

    @Override
    public List<StoredBot> findAll() {
        return List.copyOf(jdbc.query(
                "SELECT " + SELECT_COLUMNS + " FROM bots ORDER BY created_at, id",
                ROW_MAPPER));
    }

    @Override
    public List<StoredBot> findEnabled() {
        return List.copyOf(jdbc.query(
                "SELECT " + SELECT_COLUMNS + " FROM bots WHERE enabled = 1 ORDER BY created_at, id",
                ROW_MAPPER));
    }

    @Override
    public void insert(StoredBot bot) {
        Objects.requireNonNull(bot, "bot must not be null");
        BotDefinition definition = bot.definition();
        SecretCiphertext secret = bot.appSecret();
        int inserted = jdbc.update("""
                        INSERT INTO bots (
                            id, display_name, app_id, environment,
                            app_secret_ciphertext, app_secret_key_id,
                            intents, shard_index, shard_count, max_media_upload_bytes, enabled, revision,
                            created_at, updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                definition.id().toString(),
                definition.displayName(),
                definition.appId().value(),
                definition.environment().name(),
                secret.ciphertext(),
                secret.keyId(),
                definition.intents().bits(),
                definition.shardSpec().index(),
                definition.shardSpec().count(),
                definition.maxMediaUploadBytes(),
                definition.enabled() ? 1 : 0,
                definition.revision().value(),
                definition.createdAt().toString(),
                definition.updatedAt().toString());
        requireSingleRow(inserted, "insert");
    }

    @Override
    public BotRevision update(StoredBot bot, BotRevision expectedRevision) {
        Objects.requireNonNull(bot, "bot must not be null");
        Objects.requireNonNull(expectedRevision, "expectedRevision must not be null");
        BotDefinition definition = bot.definition();
        if (!definition.revision().equals(expectedRevision)) {
            throw new IllegalArgumentException("bot revision must equal expectedRevision");
        }

        BotRevision nextRevision = expectedRevision.next();
        SecretCiphertext secret = bot.appSecret();
        int updated = jdbc.update("""
                        UPDATE bots
                        SET display_name = ?,
                            app_id = ?,
                            environment = ?,
                            app_secret_ciphertext = ?,
                            app_secret_key_id = ?,
                            intents = ?,
                            shard_index = ?,
                            shard_count = ?,
                            max_media_upload_bytes = ?,
                            enabled = ?,
                            revision = ?,
                            updated_at = ?
                        WHERE id = ? AND revision = ?
                        """,
                definition.displayName(),
                definition.appId().value(),
                definition.environment().name(),
                secret.ciphertext(),
                secret.keyId(),
                definition.intents().bits(),
                definition.shardSpec().index(),
                definition.shardSpec().count(),
                definition.maxMediaUploadBytes(),
                definition.enabled() ? 1 : 0,
                nextRevision.value(),
                definition.updatedAt().toString(),
                definition.id().toString(),
                expectedRevision.value());
        if (updated == 0) {
            throw new OptimisticLockException(definition.id(), expectedRevision);
        }
        requireSingleRow(updated, "update");
        return nextRevision;
    }

    @Override
    public boolean delete(BotId id) {
        Objects.requireNonNull(id, "id must not be null");
        Boolean deleted = transaction.execute(status -> {
            String value = id.toString();
            jdbc.update("DELETE FROM plugin_deliveries WHERE binding_id IN (SELECT id FROM bot_plugins WHERE bot_id=?)", value);
            jdbc.update("DELETE FROM bot_plugins WHERE bot_id=?", value);
            jdbc.update("DELETE FROM outbox_jobs WHERE bot_id=?", value);
            jdbc.update("DELETE FROM event_inbox WHERE bot_id=?", value);
            return jdbc.update("DELETE FROM bots WHERE id=?", value) == 1;
        });
        return Boolean.TRUE.equals(deleted);
    }

    private static void requireSingleRow(int affectedRows, String operation) {
        if (affectedRows != 1) {
            throw new IllegalStateException(operation + " affected " + affectedRows + " rows instead of one");
        }
    }
}
