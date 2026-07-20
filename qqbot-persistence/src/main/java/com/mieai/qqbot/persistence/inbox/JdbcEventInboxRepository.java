package com.mieai.qqbot.persistence.inbox;

import static com.mieai.qqbot.persistence.internal.PersistenceValidation.requireIdentifier;
import static com.mieai.qqbot.persistence.internal.PersistenceValidation.requirePayload;
import static com.mieai.qqbot.persistence.internal.PersistenceValidation.requireToken;

import com.mieai.qqbot.persistence.internal.UtcTimestampCodec;
import com.mieai.qqbot.persistence.internal.DatabaseExceptionClassifier;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** JDBC Inbox repository using a database uniqueness constraint for deduplication. */
public final class JdbcEventInboxRepository implements EventInboxRepository {
    private static final String SELECT_COLUMNS = """
            id, environment, bot_id, event_type, platform_event_id, payload,
            status, attempt, available_at, lease_owner, lease_until,
            fencing_token, last_error, received_at, updated_at
            """;
    /** Listing never needs raw payloads; keep large Gateway frames out of the result set. */
    private static final String LIST_SELECT_COLUMNS = """
            id, environment, bot_id, event_type, platform_event_id,
            '_' AS payload,
            status, attempt, available_at, lease_owner, lease_until,
            fencing_token, SUBSTR(last_error, 1, 1024) AS last_error,
            received_at, updated_at
            """;
    private static final InboxEventRowMapper ROW_MAPPER = new InboxEventRowMapper();

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public JdbcEventInboxRepository(DataSource dataSource) {
        jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource must not be null"));
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @Override
    public InboxInsertResult insertOrGet(IncomingEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        String receivedAt = UtcTimestampCodec.format(event.receivedAt());
        boolean inserted = false;
        try {
            inserted = jdbc.update("""
                            INSERT INTO event_inbox (
                                id, environment, bot_id, event_type, platform_event_id, payload,
                                status, attempt, available_at, lease_owner, lease_until,
                                fencing_token, last_error, received_at, updated_at
                            ) VALUES (?, ?, ?, ?, ?, ?, 'RECEIVED', 0, ?, NULL, NULL, 0, NULL, ?, ?)
                            """,
                    event.id().toString(),
                    event.environment().name(),
                    event.botId().toString(),
                    event.eventType(),
                    event.platformEventId(),
                    event.payload(),
                    receivedAt,
                    receivedAt,
                    receivedAt) == 1;
        } catch (DataAccessException exception) {
            if (!DatabaseExceptionClassifier.isDuplicateKey(exception)) {
                throw exception;
            }
            // The unique event key is the cross-database deduplication boundary.
        }

        InboxEvent stored = findByUniqueKey(event)
                .orElseThrow(() -> new IllegalStateException("Inbox insert did not create or find an event"));
        return new InboxInsertResult(inserted, stored);
    }

    @Override
    public Optional<InboxEvent> findById(UUID id) {
        requireIdentifier(id, "id");
        List<InboxEvent> matches = jdbc.query(
                "SELECT " + SELECT_COLUMNS + " FROM event_inbox WHERE id = ?",
                ROW_MAPPER,
                id.toString());
        return matches.stream().findFirst();
    }

    @Override
    public InboxPage query(InboxQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        StringBuilder sql = new StringBuilder("SELECT ")
                .append(LIST_SELECT_COLUMNS)
                .append(" FROM event_inbox WHERE 1 = 1");
        java.util.ArrayList<Object> arguments = new java.util.ArrayList<>();

        query.environment().ifPresent(environment -> {
            sql.append(" AND environment = ?");
            arguments.add(environment.name());
        });
        query.botId().ifPresent(botId -> {
            sql.append(" AND bot_id = ?");
            arguments.add(botId.toString());
        });
        query.status().ifPresent(status -> {
            sql.append(" AND status = ?");
            arguments.add(status.name());
        });
        query.eventType().ifPresent(eventType -> {
            sql.append(" AND event_type = ?");
            arguments.add(eventType);
        });
        query.search().ifPresent(search -> {
            String pattern = "%" + escapeLike(search.toLowerCase(Locale.ROOT)) + "%";
            sql.append(" AND (LOWER(event_type) LIKE ? ESCAPE '!'"
                    + " OR LOWER(platform_event_id) LIKE ? ESCAPE '!')");
            arguments.add(pattern);
            arguments.add(pattern);
        });

        query.cursor().ifPresent(cursor -> {
            Cursor decoded = decodeCursor(cursor);
            String receivedAt = UtcTimestampCodec.format(decoded.receivedAt());
            sql.append(" AND (received_at < ? OR (received_at = ? AND id < ?))");
            arguments.add(receivedAt);
            arguments.add(receivedAt);
            arguments.add(decoded.id().toString());
        });

        sql.append(" ORDER BY received_at DESC, id DESC LIMIT ?");
        arguments.add(query.limit() + 1);
        List<InboxEvent> rows = jdbc.query(
                sql.toString(), ROW_MAPPER, arguments.toArray());
        boolean hasMore = rows.size() > query.limit();
        List<InboxEvent> events = hasMore
                ? List.copyOf(rows.subList(0, query.limit()))
                : List.copyOf(rows);
        Optional<String> nextCursor = hasMore
                ? Optional.of(encodeCursor(events.getLast()))
                : Optional.empty();
        return new InboxPage(events, nextCursor);
    }

    @Override
    public Optional<InboxEvent> claimNext(String leaseOwner, Instant now, Duration leaseDuration) {
        return claimNextInternal(leaseOwner, null, now, leaseDuration);
    }

    @Override
    public Optional<InboxEvent> claimNextOwned(
            String leaseOwner, String botLeaseOwner, Instant now, Duration leaseDuration) {
        requireToken(botLeaseOwner, "botLeaseOwner");
        return claimNextInternal(leaseOwner, botLeaseOwner, now, leaseDuration);
    }

    private Optional<InboxEvent> claimNextInternal(
            String leaseOwner, String botLeaseOwner, Instant now, Duration leaseDuration) {
        requireToken(leaseOwner, "leaseOwner");
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(leaseDuration, "leaseDuration must not be null");
        if (leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
        Instant leaseUntil = now.plus(leaseDuration);
        String nowText = UtcTimestampCodec.format(now);
        Optional<InboxEvent> result = transaction.execute(status -> {
            String product = jdbc.execute((ConnectionCallback<String>) connection ->
                    connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT));
            if (product != null && product.contains("sqlite")) {
                return botLeaseOwner == null
                        ? claimSQLite(leaseOwner, leaseUntil, nowText)
                        : claimSQLiteOwned(leaseOwner, botLeaseOwner, leaseUntil, nowText);
            }
            if (product != null && (product.contains("mysql") || product.contains("postgresql"))) {
                return botLeaseOwner == null
                        ? claimLocked(leaseOwner, leaseUntil, nowText)
                        : claimLockedOwned(leaseOwner, botLeaseOwner, leaseUntil, nowText);
            }
            throw new IllegalStateException("Unsupported database product: " + product);
        });
        return result == null ? Optional.empty() : result;
    }

    private Optional<InboxEvent> claimSQLiteOwned(
            String owner, String botLeaseOwner, Instant until, String now) {
        List<InboxEvent> claimed = jdbc.query("""
                WITH candidate AS (
                    SELECT e.id FROM event_inbox e
                    WHERE (((e.status='RECEIVED' AND e.available_at <= ?)
                        OR (e.status='PROCESSING' AND e.lease_until <= ?))
                        AND EXISTS (SELECT 1 FROM bot_leases l
                            JOIN bots configured
                              ON configured.id=l.bot_id AND configured.shard_index=l.shard_index
                            WHERE l.bot_id=e.bot_id AND l.owner_id=? AND l.lease_until > ?))
                    ORDER BY CASE WHEN e.status='PROCESSING' THEN e.lease_until ELSE e.available_at END,
                        e.received_at, e.id LIMIT 1
                )
                UPDATE event_inbox
                SET status='PROCESSING', attempt=attempt+1, lease_owner=?, lease_until=?,
                    fencing_token=fencing_token+1, updated_at=?
                WHERE id=(SELECT id FROM candidate)
                RETURNING %s
                """.formatted(SELECT_COLUMNS), new InboxEventRowMapper(), now, now,
                botLeaseOwner, now, owner, UtcTimestampCodec.format(until), now);
        if (claimed.size() > 1) throw new IllegalStateException("Inbox claim returned more than one event");
        return claimed.stream().findFirst();
    }

    private Optional<InboxEvent> claimLockedOwned(
            String owner, String botLeaseOwner, Instant until, String now) {
        List<String> candidates = jdbc.query("""
                SELECT e.id FROM event_inbox e
                WHERE (((e.status='RECEIVED' AND e.available_at <= ?)
                    OR (e.status='PROCESSING' AND e.lease_until <= ?))
                    AND EXISTS (SELECT 1 FROM bot_leases l
                        JOIN bots configured
                          ON configured.id=l.bot_id AND configured.shard_index=l.shard_index
                        WHERE l.bot_id=e.bot_id AND l.owner_id=? AND l.lease_until > ?))
                ORDER BY CASE WHEN e.status='PROCESSING' THEN e.lease_until ELSE e.available_at END,
                    e.received_at, e.id LIMIT 1 FOR UPDATE SKIP LOCKED
                """, (resultSet, rowNumber) -> resultSet.getString(1), now, now,
                botLeaseOwner, now);
        if (candidates.isEmpty()) return Optional.empty();
        String id = candidates.getFirst();
        int updated = jdbc.update("""
                UPDATE event_inbox SET status='PROCESSING', attempt=attempt+1,
                    lease_owner=?, lease_until=?, fencing_token=fencing_token+1, updated_at=?
                WHERE id=?
                """, owner, UtcTimestampCodec.format(until), now, id);
        if (updated != 1) throw new IllegalStateException("Inbox claim affected " + updated + " rows instead of one");
        return jdbc.query("SELECT " + SELECT_COLUMNS + " FROM event_inbox WHERE id=?",
                new InboxEventRowMapper(), id).stream().findFirst();
    }

    private Optional<InboxEvent> claimSQLite(String owner, Instant until, String nowText) {
        List<InboxEvent> claimed = jdbc.query("""
                        WITH candidate AS (
                            SELECT id FROM event_inbox
                            WHERE ((status = 'RECEIVED' AND available_at <= ?)
                                OR (status = 'PROCESSING' AND lease_until <= ?))
                            ORDER BY CASE WHEN status = 'PROCESSING' THEN lease_until ELSE available_at END,
                                     received_at, id LIMIT 1
                        )
                        UPDATE event_inbox
                        SET status = 'PROCESSING', attempt = attempt + 1,
                            lease_owner = ?, lease_until = ?, fencing_token = fencing_token + 1,
                            updated_at = ?
                        WHERE id = (SELECT id FROM candidate)
                        RETURNING %s
                        """.formatted(SELECT_COLUMNS),
                new InboxEventRowMapper(), nowText, nowText, owner,
                UtcTimestampCodec.format(until), nowText);
        if (claimed.size() > 1) throw new IllegalStateException("Inbox claim returned more than one event");
        return claimed.stream().findFirst();
    }

    private Optional<InboxEvent> claimLocked(String owner, Instant until, String nowText) {
        List<String> candidates = jdbc.query("""
                        SELECT id FROM event_inbox
                        WHERE ((status = 'RECEIVED' AND available_at <= ?)
                            OR (status = 'PROCESSING' AND lease_until <= ?))
                        ORDER BY CASE WHEN status = 'PROCESSING' THEN lease_until ELSE available_at END,
                                 received_at, id LIMIT 1 FOR UPDATE SKIP LOCKED
                        """, (resultSet, rowNumber) -> resultSet.getString(1), nowText, nowText);
        if (candidates.isEmpty()) return Optional.empty();
        String id = candidates.getFirst();
        int updated = jdbc.update("""
                        UPDATE event_inbox
                        SET status = 'PROCESSING', attempt = attempt + 1,
                            lease_owner = ?, lease_until = ?, fencing_token = fencing_token + 1,
                            updated_at = ?
                        WHERE id = ?
                        """, owner, UtcTimestampCodec.format(until), nowText, id);
        if (updated != 1) throw new IllegalStateException("Inbox claim affected " + updated + " rows instead of one");
        return jdbc.query("SELECT " + SELECT_COLUMNS + " FROM event_inbox WHERE id = ?",
                        new InboxEventRowMapper(), id).stream().findFirst();
    }

    @Override
    public void markDispatched(UUID id, long fencingToken, Instant now) {
        transition(id, fencingToken, now, InboxStatus.DISPATCHED, null, null);
    }

    @Override
    public void markRetry(UUID id, long fencingToken, Instant now, Instant availableAt, String error) {
        Objects.requireNonNull(availableAt, "availableAt must not be null");
        if (!availableAt.isAfter(now)) throw new IllegalArgumentException("availableAt must be after now");
        transition(id, fencingToken, now, InboxStatus.RECEIVED, availableAt, error);
    }

    @Override
    public void markDeadLetter(UUID id, long fencingToken, Instant now, String reason) {
        transition(id, fencingToken, now, InboxStatus.DEAD_LETTER, now, reason);
    }

    private void transition(UUID id, long fencingToken, Instant now, InboxStatus target,
            Instant availableAt, String error) {
        requireIdentifier(id, "id");
        if (fencingToken < 1L) throw new IllegalArgumentException("fencingToken must be positive");
        Objects.requireNonNull(now, "now must not be null");
        if (error != null) requirePayload(error, "error");
        String available = availableAt == null ? UtcTimestampCodec.format(now) : UtcTimestampCodec.format(availableAt);
        int updated = jdbc.update("""
                        UPDATE event_inbox
                        SET status = ?, available_at = ?, lease_owner = NULL, lease_until = NULL,
                            last_error = ?, updated_at = ?
                        WHERE id = ? AND status = 'PROCESSING' AND fencing_token = ? AND lease_until > ?
                        """, target.name(), available, error, UtcTimestampCodec.format(now),
                id.toString(), fencingToken, UtcTimestampCodec.format(now));
        if (updated == 0) throw new InboxTransitionException(id, fencingToken, target);
        if (updated != 1) throw new IllegalStateException("Inbox transition affected " + updated + " rows instead of one");
    }

    private Optional<InboxEvent> findByUniqueKey(IncomingEvent event) {
        List<InboxEvent> matches = jdbc.query("""
                        SELECT %s
                        FROM event_inbox
                        WHERE environment = ? AND bot_id = ?
                          AND event_type = ? AND platform_event_id = ?
                        """.formatted(SELECT_COLUMNS),
                ROW_MAPPER,
                event.environment().name(),
                event.botId().toString(),
                event.eventType(),
                event.platformEventId());
        return matches.stream().findFirst();
    }

    private static String encodeCursor(InboxEvent event) {
        String value = UtcTimestampCodec.format(event.receivedAt()) + "\n" + event.id();
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static Cursor decodeCursor(String encoded) {
        try {
            String value = new String(
                    Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            String[] parts = value.split("\\n", -1);
            if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
                throw new IllegalArgumentException("cursor must contain a timestamp and id");
            }
            return new Cursor(Instant.from(java.time.format.DateTimeFormatter.ISO_INSTANT.parse(parts[0])),
                    UUID.fromString(parts[1]));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("cursor is invalid", exception);
        }
    }

    private static String escapeLike(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    private record Cursor(Instant receivedAt, UUID id) {}
}
