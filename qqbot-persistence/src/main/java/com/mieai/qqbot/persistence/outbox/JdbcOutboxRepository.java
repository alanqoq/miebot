package com.mieai.qqbot.persistence.outbox;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** JDBC Outbox repository with safe claim operations for all supported database dialects. */
public final class JdbcOutboxRepository implements OutboxRepository {
    private static final String SELECT_COLUMNS = """
            id, environment, bot_id, source_event_id, job_type, dedup_key, payload,
            status, attempt, available_at, lease_owner, lease_until,
            fencing_token, last_error, created_at, updated_at, completed_at
            """;
    /** Listing never needs raw payloads; avoid transferring potentially huge message bodies. */
    private static final String LIST_SELECT_COLUMNS = """
            id, environment, bot_id, source_event_id, job_type, dedup_key,
            '_' AS payload,
            status, attempt, available_at, lease_owner, lease_until,
            fencing_token, SUBSTR(last_error, 1, 1024) AS last_error,
            created_at, updated_at, completed_at
            """;
    private static final OutboxJobRowMapper ROW_MAPPER = new OutboxJobRowMapper();

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public JdbcOutboxRepository(DataSource dataSource) {
        DataSource requiredDataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        jdbc = new JdbcTemplate(requiredDataSource);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(requiredDataSource));
    }

    @Override
    public void create(NewOutboxJob job) {
        Objects.requireNonNull(job, "job must not be null");
        String createdAt = UtcTimestampCodec.format(job.createdAt());
        int inserted = jdbc.update("""
                        INSERT INTO outbox_jobs (
                            id, environment, bot_id, source_event_id, job_type, dedup_key, payload,
                            status, attempt, available_at, lease_owner, lease_until,
                            fencing_token, last_error, created_at, updated_at, completed_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', 0, ?, NULL, NULL, 0, NULL, ?, ?, NULL)
                        """,
                job.id().toString(),
                job.environment().name(),
                job.botId().toString(),
                job.sourceEventId().map(UUID::toString).orElse(null),
                job.jobType(),
                job.dedupKey().orElse(null),
                job.payload(),
                UtcTimestampCodec.format(job.availableAt()),
                createdAt,
                createdAt);
        if (inserted != 1) {
            throw new IllegalStateException("Outbox insert affected " + inserted + " rows instead of one");
        }
    }

    @Override
    public Optional<OutboxJob> findById(UUID id) {
        requireIdentifier(id, "id");
        List<OutboxJob> matches = jdbc.query(
                "SELECT " + SELECT_COLUMNS + " FROM outbox_jobs WHERE id = ?",
                ROW_MAPPER,
                id.toString());
        return matches.stream().findFirst();
    }

    @Override
    public OutboxPage query(OutboxQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        StringBuilder sql = new StringBuilder("SELECT ")
                .append(LIST_SELECT_COLUMNS)
                .append(" FROM outbox_jobs WHERE 1 = 1");
        ArrayList<Object> arguments = new ArrayList<>();

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
        query.jobType().ifPresent(jobType -> {
            sql.append(" AND job_type = ?");
            arguments.add(jobType);
        });
        query.search().ifPresent(search -> {
            String pattern = "%" + escapeLike(search.toLowerCase(Locale.ROOT)) + "%";
            sql.append(" AND (LOWER(id) LIKE ? ESCAPE '!'"
                    + " OR LOWER(bot_id) LIKE ? ESCAPE '!'"
                    + " OR LOWER(job_type) LIKE ? ESCAPE '!'"
                    + " OR LOWER(COALESCE(dedup_key, '')) LIKE ? ESCAPE '!'")
                    .append(" OR LOWER(COALESCE(source_event_id, '')) LIKE ? ESCAPE '!'")
                    .append(" OR LOWER(COALESCE(last_error, '')) LIKE ? ESCAPE '!')");
            arguments.add(pattern);
            arguments.add(pattern);
            arguments.add(pattern);
            arguments.add(pattern);
            arguments.add(pattern);
            arguments.add(pattern);
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
        List<OutboxJob> rows = jdbc.query(sql.toString(), ROW_MAPPER, arguments.toArray());
        boolean hasMore = rows.size() > query.limit();
        List<OutboxJob> jobs = hasMore
                ? List.copyOf(rows.subList(0, query.limit()))
                : List.copyOf(rows);
        Optional<String> nextCursor = hasMore
                ? Optional.of(encodeCursor(jobs.getLast()))
                : Optional.empty();
        return new OutboxPage(jobs, nextCursor);
    }

    @Override
    public OutboxQueueStats statistics() {
        return jdbc.queryForObject("""
                        SELECT COUNT(*) AS total_count,
                               COALESCE(SUM(CASE WHEN status = 'PENDING' THEN 1 ELSE 0 END), 0) AS pending_count,
                               COALESCE(SUM(CASE WHEN status = 'IN_PROGRESS' THEN 1 ELSE 0 END), 0) AS in_progress_count,
                               COALESCE(SUM(CASE WHEN status = 'RETRY_WAIT' THEN 1 ELSE 0 END), 0) AS retry_wait_count,
                               COALESCE(SUM(CASE WHEN status = 'SUCCEEDED' THEN 1 ELSE 0 END), 0) AS succeeded_count,
                               COALESCE(SUM(CASE WHEN status = 'RESULT_UNKNOWN' THEN 1 ELSE 0 END), 0) AS result_unknown_count,
                               COALESCE(SUM(CASE WHEN status = 'DEAD_LETTER' THEN 1 ELSE 0 END), 0) AS dead_letter_count
                        FROM outbox_jobs
                        """,
                (resultSet, rowNumber) -> new OutboxQueueStats(
                        resultSet.getLong("total_count"),
                        resultSet.getLong("pending_count"),
                        resultSet.getLong("in_progress_count"),
                        resultSet.getLong("retry_wait_count"),
                        resultSet.getLong("succeeded_count"),
                        resultSet.getLong("result_unknown_count"),
                        resultSet.getLong("dead_letter_count")));
    }

    @Override
    public Optional<OutboxJob> claimNext(String leaseOwner, Instant now, Duration leaseDuration) {
        requireToken(leaseOwner, "leaseOwner");
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(leaseDuration, "leaseDuration must not be null");
        if (leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
        Instant leaseUntil = now.plus(leaseDuration);
        String nowText = UtcTimestampCodec.format(now);

        Optional<OutboxJob> claimed = transaction.execute(status -> {
            String databaseProduct = jdbc.execute((ConnectionCallback<String>) connection ->
                    connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT));
            if (databaseProduct != null && databaseProduct.contains("sqlite")) {
                return claimSQLite(leaseOwner, leaseUntil, nowText);
            }
            if (databaseProduct != null
                    && (databaseProduct.contains("mysql") || databaseProduct.contains("postgresql"))) {
                return claimWithLockedCandidate(leaseOwner, leaseUntil, nowText);
            }
            throw new IllegalStateException("Unsupported database product: " + databaseProduct);
        });
        return claimed == null ? Optional.empty() : claimed;
    }

    private Optional<OutboxJob> claimSQLite(String leaseOwner, Instant leaseUntil, String nowText) {
        List<OutboxJob> claimed = jdbc.query("""
                        WITH candidate AS (
                            SELECT id
                            FROM outbox_jobs
                            WHERE (
                                status IN ('PENDING', 'RETRY_WAIT')
                                AND available_at <= ?
                            ) OR (
                                status = 'IN_PROGRESS'
                                AND lease_until <= ?
                            )
                            ORDER BY
                                CASE WHEN status = 'IN_PROGRESS' THEN lease_until ELSE available_at END,
                                created_at,
                                id
                            LIMIT 1
                        )
                        UPDATE outbox_jobs
                        SET status = 'IN_PROGRESS',
                            attempt = attempt + 1,
                            lease_owner = ?,
                            lease_until = ?,
                            fencing_token = fencing_token + 1,
                            updated_at = ?,
                            completed_at = NULL
                        WHERE id = (SELECT id FROM candidate)
                        RETURNING %s
                        """.formatted(SELECT_COLUMNS),
                ROW_MAPPER,
                nowText,
                nowText,
                leaseOwner,
                UtcTimestampCodec.format(leaseUntil),
                nowText);
        if (claimed.size() > 1) {
            throw new IllegalStateException("Outbox claim returned more than one job");
        }
        return claimed.stream().findFirst();
    }

    private Optional<OutboxJob> claimWithLockedCandidate(
            String leaseOwner,
            Instant leaseUntil,
            String nowText) {
        List<String> candidates = jdbc.query("""
                        SELECT id
                        FROM outbox_jobs
                        WHERE (
                            status IN ('PENDING', 'RETRY_WAIT')
                            AND available_at <= ?
                        ) OR (
                            status = 'IN_PROGRESS'
                            AND lease_until <= ?
                        )
                        ORDER BY
                            CASE WHEN status = 'IN_PROGRESS' THEN lease_until ELSE available_at END,
                            created_at,
                            id
                        LIMIT 1
                        FOR UPDATE SKIP LOCKED
                        """,
                (resultSet, rowNumber) -> resultSet.getString(1),
                nowText,
                nowText);
        if (candidates.isEmpty()) {
            return Optional.empty();
        }

        String id = candidates.getFirst();
        int updated = jdbc.update("""
                        UPDATE outbox_jobs
                        SET status = 'IN_PROGRESS',
                            attempt = attempt + 1,
                            lease_owner = ?,
                            lease_until = ?,
                            fencing_token = fencing_token + 1,
                            updated_at = ?,
                            completed_at = NULL
                        WHERE id = ?
                        """,
                leaseOwner,
                UtcTimestampCodec.format(leaseUntil),
                nowText,
                id);
        if (updated != 1) {
            throw new IllegalStateException("Outbox claim affected " + updated + " rows instead of one");
        }
        return jdbc.query(
                        "SELECT " + SELECT_COLUMNS + " FROM outbox_jobs WHERE id = ?",
                        ROW_MAPPER,
                        id)
                .stream()
                .findFirst();
    }

    @Override
    public void markSucceeded(UUID id, long fencingToken, Instant now) {
        requireTransitionArguments(id, fencingToken, now);
        int updated = jdbc.update("""
                        UPDATE outbox_jobs
                        SET status = 'SUCCEEDED',
                            lease_owner = NULL,
                            lease_until = NULL,
                            last_error = NULL,
                            updated_at = ?,
                            completed_at = ?
                        WHERE id = ? AND status = 'IN_PROGRESS'
                          AND fencing_token = ? AND lease_until > ?
                        """,
                UtcTimestampCodec.format(now),
                UtcTimestampCodec.format(now),
                id.toString(),
                fencingToken,
                UtcTimestampCodec.format(now));
        requireTransition(updated, id, fencingToken, OutboxStatus.SUCCEEDED);
    }

    @Override
    public void markRetry(UUID id, long fencingToken, Instant now, Instant availableAt, String error) {
        requireTransitionArguments(id, fencingToken, now);
        Objects.requireNonNull(availableAt, "availableAt must not be null");
        requirePayload(error, "error");
        if (!availableAt.isAfter(now)) {
            throw new IllegalArgumentException("availableAt must be after now");
        }
        int updated = jdbc.update("""
                        UPDATE outbox_jobs
                        SET status = 'RETRY_WAIT',
                            available_at = ?,
                            lease_owner = NULL,
                            lease_until = NULL,
                            last_error = ?,
                            updated_at = ?,
                            completed_at = NULL
                        WHERE id = ? AND status = 'IN_PROGRESS'
                          AND fencing_token = ? AND lease_until > ?
                        """,
                UtcTimestampCodec.format(availableAt),
                error,
                UtcTimestampCodec.format(now),
                id.toString(),
                fencingToken,
                UtcTimestampCodec.format(now));
        requireTransition(updated, id, fencingToken, OutboxStatus.RETRY_WAIT);
    }

    @Override
    public void markResultUnknown(UUID id, long fencingToken, Instant now, String reason) {
        markTerminal(id, fencingToken, now, reason, OutboxStatus.RESULT_UNKNOWN);
    }

    @Override
    public void markDeadLetter(UUID id, long fencingToken, Instant now, String reason) {
        markTerminal(id, fencingToken, now, reason, OutboxStatus.DEAD_LETTER);
    }

    private void markTerminal(
            UUID id,
            long fencingToken,
            Instant now,
            String reason,
            OutboxStatus targetStatus) {
        requireTransitionArguments(id, fencingToken, now);
        requirePayload(reason, "reason");
        int updated = jdbc.update("""
                        UPDATE outbox_jobs
                        SET status = ?,
                            lease_owner = NULL,
                            lease_until = NULL,
                            last_error = ?,
                            updated_at = ?,
                            completed_at = ?
                        WHERE id = ? AND status = 'IN_PROGRESS'
                          AND fencing_token = ? AND lease_until > ?
                        """,
                targetStatus.name(),
                reason,
                UtcTimestampCodec.format(now),
                UtcTimestampCodec.format(now),
                id.toString(),
                fencingToken,
                UtcTimestampCodec.format(now));
        requireTransition(updated, id, fencingToken, targetStatus);
    }

    private static void requireTransitionArguments(UUID id, long fencingToken, Instant now) {
        requireIdentifier(id, "id");
        Objects.requireNonNull(now, "now must not be null");
        if (fencingToken < 1L) {
            throw new IllegalArgumentException("fencingToken must be positive");
        }
    }

    private static void requireTransition(
            int affectedRows,
            UUID id,
            long fencingToken,
            OutboxStatus targetStatus) {
        if (affectedRows == 0) {
            throw new OutboxTransitionException(id, fencingToken, targetStatus);
        }
        if (affectedRows != 1) {
            throw new IllegalStateException("Outbox transition affected " + affectedRows + " rows instead of one");
        }
    }

    private static String encodeCursor(OutboxJob job) {
        String value = UtcTimestampCodec.format(job.createdAt()) + "\n" + job.id();
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
            Instant createdAt = Instant.from(
                    java.time.format.DateTimeFormatter.ISO_INSTANT.parse(parts[0]));
            UUID id = UUID.fromString(parts[1]);
            requireIdentifier(id, "cursor id");
            if (!id.toString().equalsIgnoreCase(parts[1])) {
                throw new IllegalArgumentException("cursor id must use canonical UUID format");
            }
            return new Cursor(createdAt, id);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("cursor is invalid", exception);
        }
    }

    private static String escapeLike(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    private record Cursor(Instant createdAt, UUID id) {}
}
