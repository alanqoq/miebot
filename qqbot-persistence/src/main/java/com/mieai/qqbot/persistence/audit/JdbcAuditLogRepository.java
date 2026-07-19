package com.mieai.qqbot.persistence.audit;

import com.mieai.qqbot.persistence.internal.UtcTimestampCodec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcAuditLogRepository implements AuditLogRepository {
    private static final String COLUMNS = "id, actor_username, action, resource_path, outcome_status, remote_address, trace_id, created_at";
    private final JdbcTemplate jdbc;

    public JdbcAuditLogRepository(DataSource dataSource) {
        jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource must not be null"));
    }

    @Override
    public void append(AuditLog log) {
        Objects.requireNonNull(log, "log must not be null");
        int inserted = jdbc.update("""
                INSERT INTO audit_logs (id, actor_username, action, resource_path, outcome_status,
                    remote_address, trace_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, log.id().toString(), log.actorUsername().orElse(null), log.action(),
                log.resourcePath(), log.outcomeStatus(), log.remoteAddress().orElse(null),
                log.traceId().orElse(null), UtcTimestampCodec.format(log.createdAt()));
        if (inserted != 1) throw new IllegalStateException("Audit insert did not affect one row");
    }

    @Override
    public AuditLogPage query(
            int limit, Optional<String> cursor, Optional<String> actor, Optional<String> action) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be between 1 and 100");
        Objects.requireNonNull(cursor, "cursor must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(action, "action must not be null");
        StringBuilder sql = new StringBuilder("SELECT ").append(COLUMNS)
                .append(" FROM audit_logs WHERE 1=1");
        ArrayList<Object> arguments = new ArrayList<>();
        actor.ifPresent(value -> {
            sql.append(" AND actor_username=?");
            arguments.add(value);
        });
        action.ifPresent(value -> {
            sql.append(" AND action=?");
            arguments.add(value);
        });
        cursor.ifPresent(value -> {
            Cursor decoded = decodeCursor(value);
            String createdAt = UtcTimestampCodec.format(decoded.createdAt());
            sql.append(" AND (created_at < ? OR (created_at = ? AND id < ?))");
            arguments.add(createdAt);
            arguments.add(createdAt);
            arguments.add(decoded.id().toString());
        });
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT ?");
        arguments.add(limit + 1);
        List<AuditLog> rows = jdbc.query(sql.toString(), (resultSet, rowNumber) -> new AuditLog(
                UUID.fromString(resultSet.getString("id")),
                Optional.ofNullable(resultSet.getString("actor_username")),
                resultSet.getString("action"), resultSet.getString("resource_path"),
                resultSet.getInt("outcome_status"),
                Optional.ofNullable(resultSet.getString("remote_address")),
                Optional.ofNullable(resultSet.getString("trace_id")),
                UtcTimestampCodec.parse(resultSet.getString("created_at"))), arguments.toArray());
        boolean hasMore = rows.size() > limit;
        List<AuditLog> logs = hasMore ? List.copyOf(rows.subList(0, limit)) : List.copyOf(rows);
        return new AuditLogPage(logs, hasMore
                ? Optional.of(encodeCursor(logs.getLast())) : Optional.empty());
    }

    private static String encodeCursor(AuditLog log) {
        String raw = UtcTimestampCodec.format(log.createdAt()) + "|" + log.id();
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static Cursor decodeCursor(String encoded) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            int separator = raw.indexOf('|');
            if (separator <= 0 || separator == raw.length() - 1 || raw.indexOf('|', separator + 1) >= 0) {
                throw new IllegalArgumentException("cursor is invalid");
            }
            return new Cursor(UtcTimestampCodec.parse(raw.substring(0, separator)),
                    UUID.fromString(raw.substring(separator + 1)));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("cursor is invalid", exception);
        }
    }

    private record Cursor(Instant createdAt, UUID id) {}
}
