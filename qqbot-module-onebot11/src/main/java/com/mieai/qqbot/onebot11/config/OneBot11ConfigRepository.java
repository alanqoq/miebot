package com.mieai.qqbot.onebot11.config;

import com.mieai.qqbot.domain.bot.BotId;
import com.mieai.qqbot.runtime.security.EncryptedConfigurationValue;
import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import javax.sql.DataSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

public final class OneBot11ConfigRepository {
    private static final String COLUMNS = """
            bot_id, enabled, forward_enabled, forward_bind_address, forward_port,
            reverse_enabled, reverse_url, access_token_ciphertext, access_token_key_id,
            heartbeat_enabled, heartbeat_interval_ms, reconnect_interval_ms,
            revision, created_at, updated_at
            """;
    private static final RowMapper<OneBot11Config> ROW_MAPPER =
            OneBot11ConfigRepository::map;

    private final JdbcTemplate jdbc;

    public OneBot11ConfigRepository(DataSource dataSource) {
        jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource must not be null"));
    }

    public Optional<OneBot11Config> find(BotId botId) {
        Objects.requireNonNull(botId, "botId must not be null");
        return jdbc.query("SELECT " + COLUMNS + " FROM onebot11_configs WHERE bot_id=?",
                ROW_MAPPER, botId.toString()).stream().findFirst();
    }

    public List<OneBot11Config> findEnabled() {
        return List.copyOf(jdbc.query(
                "SELECT " + COLUMNS + " FROM onebot11_configs WHERE enabled=1 ORDER BY bot_id",
                ROW_MAPPER));
    }

    public boolean forwardPortUsedByOther(BotId botId, int port) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM onebot11_configs
                WHERE bot_id<>? AND enabled=1 AND forward_enabled=1 AND forward_port=?
                """, Long.class, botId.toString(), port);
        return count != null && count > 0L;
    }

    public OneBot11Config save(OneBot11Config value, long expectedRevision) {
        Objects.requireNonNull(value, "value must not be null");
        if (expectedRevision < 0L || value.revision() != expectedRevision) {
            throw new IllegalArgumentException("expectedRevision is invalid");
        }
        long nextRevision = expectedRevision + 1L;
        Instant now = value.updatedAt();
        if (expectedRevision == 0L) {
            try {
                int inserted = jdbc.update("""
                        INSERT INTO onebot11_configs (
                            bot_id, enabled, forward_enabled, forward_bind_address, forward_port,
                            reverse_enabled, reverse_url, access_token_ciphertext, access_token_key_id,
                            heartbeat_enabled, heartbeat_interval_ms, reconnect_interval_ms,
                            revision, created_at, updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, parameters(value, nextRevision, value.createdAt(), now));
                requireSingle(inserted);
            } catch (DuplicateKeyException exception) {
                throw new OneBot11RevisionConflictException();
            }
        } else {
            Object[] values = parameters(value, nextRevision, value.createdAt(), now);
            int updated = jdbc.update("""
                    UPDATE onebot11_configs SET
                        enabled=?, forward_enabled=?, forward_bind_address=?, forward_port=?,
                        reverse_enabled=?, reverse_url=?, access_token_ciphertext=?, access_token_key_id=?,
                        heartbeat_enabled=?, heartbeat_interval_ms=?, reconnect_interval_ms=?,
                        revision=?, updated_at=?
                    WHERE bot_id=? AND revision=?
                    """,
                    values[1], values[2], values[3], values[4], values[5], values[6],
                    values[7], values[8], values[9], values[10], values[11], values[12],
                    values[14], values[0], expectedRevision);
            if (updated == 0) {
                throw new OneBot11RevisionConflictException();
            }
            requireSingle(updated);
        }
        return find(value.botId()).orElseThrow(
                () -> new IllegalStateException("Saved OneBot settings could not be reloaded"));
    }

    private static Object[] parameters(
            OneBot11Config value, long revision, Instant createdAt, Instant updatedAt) {
        EncryptedConfigurationValue token = value.encryptedAccessToken().orElse(null);
        return new Object[] {
                value.botId().toString(),
                value.enabled() ? 1 : 0,
                value.forwardEnabled() ? 1 : 0,
                value.forwardBindAddress(),
                value.forwardPort().isPresent() ? value.forwardPort().getAsInt() : null,
                value.reverseEnabled() ? 1 : 0,
                value.reverseUrl().map(Object::toString).orElse(null),
                token == null ? null : token.ciphertext(),
                token == null ? null : token.keyId(),
                value.heartbeatEnabled() ? 1 : 0,
                value.heartbeatIntervalMs(),
                value.reconnectIntervalMs(),
                revision,
                createdAt.toString(),
                updatedAt.toString()
        };
    }

    private static OneBot11Config map(ResultSet resultSet, int rowNumber) throws SQLException {
        int port = resultSet.getInt("forward_port");
        OptionalInt forwardPort = resultSet.wasNull() ? OptionalInt.empty() : OptionalInt.of(port);
        String reverseUrl = resultSet.getString("reverse_url");
        String ciphertext = resultSet.getString("access_token_ciphertext");
        String keyId = resultSet.getString("access_token_key_id");
        Optional<EncryptedConfigurationValue> token = ciphertext == null
                ? Optional.empty()
                : Optional.of(new EncryptedConfigurationValue(ciphertext, keyId));
        return new OneBot11Config(
                BotId.parse(resultSet.getString("bot_id")),
                resultSet.getInt("enabled") != 0,
                resultSet.getInt("forward_enabled") != 0,
                resultSet.getString("forward_bind_address"),
                forwardPort,
                resultSet.getInt("reverse_enabled") != 0,
                Optional.ofNullable(reverseUrl).map(URI::create),
                token,
                resultSet.getInt("heartbeat_enabled") != 0,
                resultSet.getInt("heartbeat_interval_ms"),
                resultSet.getInt("reconnect_interval_ms"),
                resultSet.getLong("revision"),
                Instant.parse(resultSet.getString("created_at")),
                Instant.parse(resultSet.getString("updated_at")));
    }

    private static void requireSingle(int affected) {
        if (affected != 1) {
            throw new IllegalStateException("OneBot settings write affected " + affected + " rows");
        }
    }
}
