package com.mieai.qqbot.persistence.lease;

import com.mieai.qqbot.domain.bot.BotId;
import com.mieai.qqbot.persistence.internal.UtcTimestampCodec;
import com.mieai.qqbot.persistence.internal.DatabaseExceptionClassifier;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** SQL-backed owner lease with a monotonically increasing fencing token. */
public final class JdbcBotLeaseRepository implements BotLeaseRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public JdbcBotLeaseRepository(DataSource dataSource) {
        DataSource required = Objects.requireNonNull(dataSource, "dataSource must not be null");
        jdbc = new JdbcTemplate(required);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(required));
    }

    @Override
    public Optional<BotLease> acquire(BotId botId, int shardIndex, String ownerId,
            Instant now, Duration duration) {
        validate(botId, shardIndex, ownerId, now, duration);
        String bot = botId.toString();
        String current = UtcTimestampCodec.format(now);
        String until = UtcTimestampCodec.format(now.plus(duration));
        return transaction.execute(status -> {
            int updated = jdbc.update("""
                    UPDATE bot_leases SET owner_id=?, lease_until=?, fencing_token=fencing_token+1, updated_at=?
                    WHERE bot_id=? AND shard_index=? AND (lease_until <= ? OR owner_id=?)
                      AND EXISTS (SELECT 1 FROM bots b WHERE b.id=? AND b.shard_index=?)
                    """, ownerId, until, current, bot, shardIndex, current, ownerId, bot, shardIndex);
            if (updated == 1) return find(bot, shardIndex);
            try {
                int inserted = jdbc.update("""
                        INSERT INTO bot_leases (bot_id, shard_index, owner_id, lease_until, fencing_token, updated_at)
                        SELECT b.id, b.shard_index, ?, ?, 1, ?
                        FROM bots b WHERE b.id=? AND b.shard_index=?
                        """, ownerId, until, current, bot, shardIndex);
                return inserted == 1 ? find(bot, shardIndex) : Optional.empty();
            } catch (DataAccessException duplicate) {
                // Another instance won the insert race; do not steal a live lease.
                if (!DatabaseExceptionClassifier.isDuplicateKey(duplicate)) {
                    throw duplicate;
                }
                return Optional.empty();
            }
        });
    }

    @Override
    public Optional<BotLease> acquire(BotId botId, int shardIndex, String ownerId,
            Instant now, Duration duration, Map<String, String> pluginHashes) {
        Objects.requireNonNull(pluginHashes, "pluginHashes must not be null");
        registerPluginHashes(ownerId, pluginHashes, now, duration);
        if (!pluginHashesMatch(botId, pluginHashes)) return Optional.empty();
        return acquire(botId, shardIndex, ownerId, now, duration);
    }

    @Override
    public boolean isOwned(BotId botId, int shardIndex, String ownerId, Instant now) {
        Objects.requireNonNull(botId, "botId must not be null");
        Objects.requireNonNull(now, "now must not be null");
        if (ownerId == null || ownerId.isBlank()) throw new IllegalArgumentException("ownerId is invalid");
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM bot_leases l
                JOIN bots b ON b.id=l.bot_id AND b.shard_index=l.shard_index
                WHERE l.bot_id=? AND l.shard_index=? AND l.owner_id=? AND l.lease_until > ?
                """, Integer.class, botId.toString(), shardIndex, ownerId, UtcTimestampCodec.format(now));
        return count != null && count == 1;
    }

    @Override
    public boolean isOwned(BotId botId, String ownerId, Instant now) {
        Objects.requireNonNull(botId, "botId must not be null");
        Objects.requireNonNull(now, "now must not be null");
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM bot_leases l
                JOIN bots b ON b.id=l.bot_id AND b.shard_index=l.shard_index
                WHERE l.bot_id=? AND l.owner_id=? AND l.lease_until > ?
                """, Integer.class, botId.toString(), ownerId, UtcTimestampCodec.format(now));
        return count != null && count > 0;
    }

    @Override
    public void registerPluginHashes(String ownerId, Map<String, String> pluginHashes,
            Instant now, Duration duration) {
        validateOwnerAndHashes(ownerId, pluginHashes);
        Objects.requireNonNull(now, "now must not be null");
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("duration must be positive");
        }
        String until = UtcTimestampCodec.format(now.plus(duration));
        pluginHashes.forEach((pluginId, sha256) -> {
            int updated = jdbc.update("""
                    UPDATE instance_plugin_hashes SET sha256=?, lease_until=?, updated_at=?
                    WHERE instance_id=? AND plugin_id=?
                    """, sha256, until, UtcTimestampCodec.format(now), ownerId, pluginId);
            if (updated == 0) {
                jdbc.update("""
                        INSERT INTO instance_plugin_hashes(instance_id, plugin_id, sha256, lease_until, updated_at)
                        VALUES (?, ?, ?, ?, ?)
                        """, ownerId, pluginId, sha256, until, UtcTimestampCodec.format(now));
            }
        });
    }

    @Override
    public void unregisterPluginHashes(String ownerId) {
        if (ownerId != null && !ownerId.isBlank()) {
            jdbc.update("DELETE FROM instance_plugin_hashes WHERE instance_id=?", ownerId);
        }
    }

    @Override
    public boolean renew(BotLease lease, Instant now, Duration duration) {
        Objects.requireNonNull(lease, "lease must not be null");
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(duration, "duration must not be null");
        if (duration.isZero() || duration.isNegative()) throw new IllegalArgumentException("duration must be positive");
        return jdbc.update("""
                UPDATE bot_leases SET lease_until=?, updated_at=?
                WHERE bot_id=? AND shard_index=? AND owner_id=? AND fencing_token=? AND lease_until > ?
                  AND EXISTS (SELECT 1 FROM bots b WHERE b.id=? AND b.shard_index=?)
                """, UtcTimestampCodec.format(now.plus(duration)), UtcTimestampCodec.format(now),
                lease.botId().toString(), lease.shardIndex(), lease.ownerId(), lease.fencingToken(),
                UtcTimestampCodec.format(now), lease.botId().toString(), lease.shardIndex()) == 1;
    }

    @Override
    public boolean renew(BotLease lease, Instant now, Duration duration,
            Map<String, String> pluginHashes) {
        Objects.requireNonNull(lease, "lease must not be null");
        Objects.requireNonNull(pluginHashes, "pluginHashes must not be null");
        registerPluginHashes(lease.ownerId(), pluginHashes, now, duration);
        return pluginHashesMatch(lease.botId(), pluginHashes) && renew(lease, now, duration);
    }

    @Override
    public boolean release(BotLease lease) {
        Objects.requireNonNull(lease, "lease must not be null");
        return jdbc.update("DELETE FROM bot_leases WHERE bot_id=? AND shard_index=? AND owner_id=? AND fencing_token=?",
                lease.botId().toString(), lease.shardIndex(), lease.ownerId(), lease.fencingToken()) == 1;
    }

    private Optional<BotLease> find(String botId, int shardIndex) {
        List<BotLease> rows = jdbc.query("SELECT bot_id, shard_index, owner_id, lease_until, fencing_token FROM bot_leases WHERE bot_id=? AND shard_index=?",
                (rs, row) -> new BotLease(BotId.parse(rs.getString("bot_id")), rs.getInt("shard_index"),
                        rs.getString("owner_id"), UtcTimestampCodec.parse(rs.getString("lease_until")),
                        rs.getLong("fencing_token")), botId, shardIndex);
        return rows.stream().findFirst();
    }

    private boolean pluginHashesMatch(BotId botId, Map<String, String> local) {
        List<Map.Entry<String, String>> required = jdbc.query("""
                SELECT a.plugin_id, a.sha256
                FROM bot_plugins b JOIN plugin_artifacts a ON a.plugin_id=b.plugin_id
                WHERE b.bot_id=? AND b.enabled=1 AND b.runtime_state='ACTIVE'
                """, (rs, row) -> Map.entry(rs.getString(1), rs.getString(2)), botId.toString());
        return required.stream().allMatch(entry -> entry.getValue().equals(local.get(entry.getKey())));
    }

    private static void validateOwnerAndHashes(String ownerId, Map<String, String> hashes) {
        if (ownerId == null || ownerId.isBlank() || ownerId.length() > 255
                || ownerId.codePoints().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("ownerId is invalid");
        }
        hashes.forEach((plugin, hash) -> {
            if (plugin == null || plugin.isBlank() || hash == null || hash.isBlank()
                    || hash.length() > 128 || hash.codePoints().anyMatch(Character::isWhitespace)) {
                throw new IllegalArgumentException("plugin hash registration is invalid");
            }
        });
    }

    private static void validate(BotId botId, int shardIndex, String ownerId, Instant now, Duration duration) {
        Objects.requireNonNull(botId, "botId must not be null");
        if (shardIndex < 0) throw new IllegalArgumentException("shardIndex must not be negative");
        if (ownerId == null || ownerId.isBlank() || ownerId.length() > 255
                || ownerId.codePoints().anyMatch(Character::isWhitespace)
                || ownerId.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("ownerId is invalid");
        }
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(duration, "duration must not be null");
        if (duration.isZero() || duration.isNegative()) throw new IllegalArgumentException("duration must be positive");
    }
}
