package com.mieai.qqbot.persistence.lease;

import com.mieai.qqbot.domain.bot.BotId;
import com.mieai.qqbot.persistence.internal.UtcTimestampCodec;
import com.mieai.qqbot.persistence.internal.DatabaseExceptionClassifier;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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
                    """, ownerId, until, current, bot, shardIndex, current, ownerId);
            if (updated == 1) return find(bot, shardIndex);
            try {
                jdbc.update("""
                        INSERT INTO bot_leases (bot_id, shard_index, owner_id, lease_until, fencing_token, updated_at)
                        VALUES (?, ?, ?, ?, 1, ?)
                        """, bot, shardIndex, ownerId, until, current);
                return find(bot, shardIndex);
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
    public boolean renew(BotLease lease, Instant now, Duration duration) {
        Objects.requireNonNull(lease, "lease must not be null");
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(duration, "duration must not be null");
        if (duration.isZero() || duration.isNegative()) throw new IllegalArgumentException("duration must be positive");
        return jdbc.update("""
                UPDATE bot_leases SET lease_until=?, updated_at=?
                WHERE bot_id=? AND shard_index=? AND owner_id=? AND fencing_token=? AND lease_until > ?
                """, UtcTimestampCodec.format(now.plus(duration)), UtcTimestampCodec.format(now),
                lease.botId().toString(), lease.shardIndex(), lease.ownerId(), lease.fencingToken(),
                UtcTimestampCodec.format(now)) == 1;
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
