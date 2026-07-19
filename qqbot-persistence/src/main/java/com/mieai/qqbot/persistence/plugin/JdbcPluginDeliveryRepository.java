package com.mieai.qqbot.persistence.plugin;

import static com.mieai.qqbot.persistence.internal.PersistenceValidation.requireIdentifier;
import static com.mieai.qqbot.persistence.internal.PersistenceValidation.requirePayload;
import static com.mieai.qqbot.persistence.internal.PersistenceValidation.requireToken;

import com.mieai.qqbot.persistence.internal.UtcTimestampCodec;
import java.time.Duration;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Fenced JDBC queue for event-to-plugin deliveries. */
public final class JdbcPluginDeliveryRepository implements PluginDeliveryRepository {
    private static final String COLUMNS = "id, event_id, binding_id, handler_id, status, attempt, available_at, lease_owner, lease_until, fencing_token, last_error, created_at, updated_at, completed_at";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public JdbcPluginDeliveryRepository(DataSource dataSource) {
        DataSource required = Objects.requireNonNull(dataSource, "dataSource must not be null");
        jdbc = new JdbcTemplate(required);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(required));
    }

    @Override
    public boolean createIfAbsent(UUID id, UUID eventId, UUID bindingId, String handlerId, Instant now) {
        requireIdentifier(id, "id");
        requireIdentifier(eventId, "eventId");
        requireIdentifier(bindingId, "bindingId");
        requireToken(handlerId, "handlerId", 128);
        Objects.requireNonNull(now, "now must not be null");
        String text = UtcTimestampCodec.format(now);
        try {
            return jdbc.update("""
                    INSERT INTO plugin_deliveries (id, event_id, binding_id, handler_id, status, attempt,
                        available_at, lease_owner, lease_until, fencing_token, last_error, created_at, updated_at, completed_at)
                    VALUES (?, ?, ?, ?, 'PENDING', 0, ?, NULL, NULL, 0, NULL, ?, ?, NULL)
                    """, id.toString(), eventId.toString(), bindingId.toString(), handlerId, text, text, text) == 1;
        } catch (DataAccessException duplicateOrFailure) {
            // A uniqueness violation means another worker already materialized this delivery.
            List<String> existing = jdbc.query("SELECT id FROM plugin_deliveries WHERE event_id=? AND binding_id=? AND handler_id=?",
                    (rs, row) -> rs.getString(1), eventId.toString(), bindingId.toString(), handlerId);
            if (!existing.isEmpty()) return false;
            throw duplicateOrFailure;
        }
    }

    @Override
    public Optional<PluginDelivery> findById(UUID id) {
        requireIdentifier(id, "id");
        return jdbc.query("SELECT " + COLUMNS + " FROM plugin_deliveries WHERE id=?",
                new PluginDeliveryRowMapper(), id.toString()).stream().findFirst();
    }

    @Override
    public PluginDeliveryPage query(PluginDeliveryQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        StringBuilder sql = new StringBuilder("SELECT ").append(COLUMNS)
                .append(" FROM plugin_deliveries WHERE 1=1");
        ArrayList<Object> arguments = new ArrayList<>();
        query.bindingId().ifPresent(bindingId -> {
            sql.append(" AND binding_id=?");
            arguments.add(bindingId.toString());
        });
        query.status().ifPresent(status -> {
            sql.append(" AND status=?");
            arguments.add(status.name());
        });
        query.search().ifPresent(search -> {
            String pattern = "%" + escapeLike(search.toLowerCase(Locale.ROOT)) + "%";
            sql.append(" AND (LOWER(id) LIKE ? ESCAPE '!' OR LOWER(event_id) LIKE ? ESCAPE '!'"
                    + " OR LOWER(binding_id) LIKE ? ESCAPE '!' OR LOWER(handler_id) LIKE ? ESCAPE '!'"
                    + " OR LOWER(COALESCE(last_error,'')) LIKE ? ESCAPE '!')");
            for (int index = 0; index < 5; index++) arguments.add(pattern);
        });
        query.cursor().ifPresent(cursor -> {
            Cursor decoded = decodeCursor(cursor);
            String createdAt = UtcTimestampCodec.format(decoded.createdAt());
            sql.append(" AND (created_at < ? OR (created_at = ? AND id < ?))");
            arguments.add(createdAt);
            arguments.add(createdAt);
            arguments.add(decoded.id().toString());
        });
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT ?");
        arguments.add(query.limit() + 1);
        List<PluginDelivery> rows = jdbc.query(
                sql.toString(), new PluginDeliveryRowMapper(), arguments.toArray());
        boolean hasMore = rows.size() > query.limit();
        List<PluginDelivery> deliveries = hasMore
                ? List.copyOf(rows.subList(0, query.limit()))
                : List.copyOf(rows);
        Optional<String> nextCursor = hasMore
                ? Optional.of(encodeCursor(deliveries.getLast()))
                : Optional.empty();
        return new PluginDeliveryPage(deliveries, nextCursor);
    }

    @Override
    public PluginDeliveryQueueStats statistics() {
        return jdbc.queryForObject("""
                SELECT COUNT(*) AS total_count,
                    COALESCE(SUM(CASE WHEN status='PENDING' THEN 1 ELSE 0 END),0) AS pending_count,
                    COALESCE(SUM(CASE WHEN status='IN_PROGRESS' THEN 1 ELSE 0 END),0) AS in_progress_count,
                    COALESCE(SUM(CASE WHEN status='RETRY_WAIT' THEN 1 ELSE 0 END),0) AS retry_wait_count,
                    COALESCE(SUM(CASE WHEN status='SUCCEEDED' THEN 1 ELSE 0 END),0) AS succeeded_count,
                    COALESCE(SUM(CASE WHEN status='DEAD_LETTER' THEN 1 ELSE 0 END),0) AS dead_letter_count,
                    COALESCE(SUM(CASE WHEN status='PAUSED' THEN 1 ELSE 0 END),0) AS paused_count
                FROM plugin_deliveries
                """, (resultSet, rowNumber) -> new PluginDeliveryQueueStats(
                        resultSet.getLong("total_count"),
                        resultSet.getLong("pending_count"),
                        resultSet.getLong("in_progress_count"),
                        resultSet.getLong("retry_wait_count"),
                        resultSet.getLong("succeeded_count"),
                        resultSet.getLong("dead_letter_count"),
                        resultSet.getLong("paused_count")));
    }

    @Override
    public Optional<PluginDelivery> claimNext(String leaseOwner, Instant now, Duration leaseDuration) {
        requireToken(leaseOwner, "leaseOwner", 255);
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(leaseDuration, "leaseDuration must not be null");
        if (leaseDuration.isZero() || leaseDuration.isNegative()) throw new IllegalArgumentException("leaseDuration must be positive");
        Instant until = now.plus(leaseDuration);
        String text = UtcTimestampCodec.format(now);
        Optional<PluginDelivery> result = transaction.execute(status -> {
            String product = jdbc.execute((ConnectionCallback<String>) connection ->
                    connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT));
            if (product != null && product.contains("sqlite")) return claimSqlite(leaseOwner, until, text);
            if (product != null && (product.contains("mysql") || product.contains("postgresql"))) return claimLocked(leaseOwner, until, text);
            throw new IllegalStateException("Unsupported database product: " + product);
        });
        return result == null ? Optional.empty() : result;
    }

    private Optional<PluginDelivery> claimSqlite(String owner, Instant until, String now) {
        List<PluginDelivery> rows = jdbc.query("""
                WITH candidate AS (
                    SELECT id FROM plugin_deliveries
                    WHERE (((status IN ('PENDING','RETRY_WAIT') AND available_at <= ?)
                        OR (status = 'IN_PROGRESS' AND lease_until <= ?))
                        AND EXISTS (SELECT 1 FROM bot_plugins b WHERE b.id = binding_id AND b.enabled = 1))
                    ORDER BY CASE WHEN status='IN_PROGRESS' THEN lease_until ELSE available_at END, created_at, id LIMIT 1
                )
                UPDATE plugin_deliveries
                SET status='IN_PROGRESS', attempt=attempt+1, lease_owner=?, lease_until=?, fencing_token=fencing_token+1,
                    updated_at=?, completed_at=NULL
                WHERE id=(SELECT id FROM candidate)
                RETURNING %s
                """.formatted(COLUMNS), new PluginDeliveryRowMapper(), now, now, owner, UtcTimestampCodec.format(until), now);
        if (rows.size() > 1) throw new IllegalStateException("Plugin delivery claim returned more than one row");
        return rows.stream().findFirst();
    }

    private Optional<PluginDelivery> claimLocked(String owner, Instant until, String now) {
        List<String> ids = jdbc.query("""
                SELECT id FROM plugin_deliveries
                WHERE (((status IN ('PENDING','RETRY_WAIT') AND available_at <= ?)
                    OR (status = 'IN_PROGRESS' AND lease_until <= ?))
                    AND EXISTS (SELECT 1 FROM bot_plugins b WHERE b.id = binding_id AND b.enabled = 1))
                ORDER BY CASE WHEN status='IN_PROGRESS' THEN lease_until ELSE available_at END, created_at, id
                LIMIT 1 FOR UPDATE SKIP LOCKED
                """, (rs, row) -> rs.getString(1), now, now);
        if (ids.isEmpty()) return Optional.empty();
        String id = ids.getFirst();
        int updated = jdbc.update("""
                UPDATE plugin_deliveries SET status='IN_PROGRESS', attempt=attempt+1, lease_owner=?, lease_until=?,
                    fencing_token=fencing_token+1, updated_at=?, completed_at=NULL WHERE id=?
                """, owner, UtcTimestampCodec.format(until), now, id);
        if (updated != 1) throw new IllegalStateException("Plugin delivery claim affected " + updated + " rows instead of one");
        return jdbc.query("SELECT " + COLUMNS + " FROM plugin_deliveries WHERE id=?", new PluginDeliveryRowMapper(), id).stream().findFirst();
    }

    @Override
    public void markSucceeded(UUID id, long token, Instant now) {
        transition(id, token, now, PluginDeliveryStatus.SUCCEEDED, now, null, null);
    }

    @Override
    public void markRetry(UUID id, long token, Instant now, Instant availableAt, String error) {
        Objects.requireNonNull(availableAt, "availableAt must not be null");
        if (!availableAt.isAfter(now)) throw new IllegalArgumentException("availableAt must be after now");
        transition(id, token, now, PluginDeliveryStatus.RETRY_WAIT, null, availableAt, error);
    }

    @Override
    public void markDeadLetter(UUID id, long token, Instant now, String reason) {
        transition(id, token, now, PluginDeliveryStatus.DEAD_LETTER, now, now, reason);
    }

    private void transition(UUID id, long token, Instant now, PluginDeliveryStatus target, Instant completed,
            Instant availableAt, String error) {
        requireIdentifier(id, "id");
        if (token < 1) throw new IllegalArgumentException("fencingToken must be positive");
        Objects.requireNonNull(now, "now must not be null");
        if (error != null) requirePayload(error, "error");
        String available = UtcTimestampCodec.format(availableAt == null ? now : availableAt);
        String completedText = completed == null ? null : UtcTimestampCodec.format(completed);
        int updated = jdbc.update("""
                UPDATE plugin_deliveries SET status=?, available_at=?, lease_owner=NULL, lease_until=NULL,
                    last_error=?, updated_at=?, completed_at=?
                WHERE id=? AND status='IN_PROGRESS' AND fencing_token=? AND lease_until > ?
                """, target.name(), available, error, UtcTimestampCodec.format(now), completedText,
                id.toString(), token, UtcTimestampCodec.format(now));
        if (updated == 0) throw new PluginDeliveryTransitionException(id, token, target);
        if (updated != 1) throw new IllegalStateException("Plugin delivery transition affected " + updated + " rows instead of one");
    }

    private static String encodeCursor(PluginDelivery delivery) {
        String raw = UtcTimestampCodec.format(delivery.createdAt()) + "|" + delivery.id();
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static Cursor decodeCursor(String encoded) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            int separator = raw.indexOf('|');
            if (separator <= 0 || separator == raw.length() - 1 || raw.indexOf('|', separator + 1) >= 0) {
                throw new IllegalArgumentException("cursor must contain a timestamp and id");
            }
            Instant createdAt = UtcTimestampCodec.parse(raw.substring(0, separator));
            UUID id = UUID.fromString(raw.substring(separator + 1));
            return new Cursor(createdAt, id);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("cursor is invalid", exception);
        }
    }

    private static String escapeLike(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    private record Cursor(Instant createdAt, UUID id) {}
}
