package com.mieai.qqbot.persistence.audit

import com.mieai.qqbot.persistence.internal.UtcTimestampCodec
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.ArrayList
import java.util.Base64
import java.util.UUID
import javax.sql.DataSource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper

class JdbcAuditLogRepository(dataSource: DataSource) : AuditLogRepository {
    private val jdbc = JdbcTemplate(dataSource)

    override fun append(log: AuditLog) {
        val inserted = jdbc.update(
            """
            INSERT INTO audit_logs (id, actor_username, action, resource_path, outcome_status,
                remote_address, trace_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            log.id.toString(),
            log.actorUsername,
            log.action,
            log.resourcePath,
            log.outcomeStatus,
            log.remoteAddress,
            log.traceId,
            UtcTimestampCodec.format(log.createdAt),
        )
        check(inserted == 1) { "Audit insert did not affect one row" }
    }

    override fun query(
        limit: Int,
        cursor: String?,
        actor: String?,
        action: String?,
    ): AuditLogPage {
        require(limit in 1..100) { "limit must be between 1 and 100" }
        val sql = StringBuilder("SELECT ").append(COLUMNS).append(" FROM audit_logs WHERE 1=1")
        val arguments = ArrayList<Any>()
        actor?.let { value ->
            sql.append(" AND actor_username=?")
            arguments.add(value)
        }
        action?.let { value ->
            sql.append(" AND action=?")
            arguments.add(value)
        }
        cursor?.let { value ->
            val decoded = decodeCursor(value)
            val createdAt = UtcTimestampCodec.format(decoded.createdAt)
            sql.append(" AND (created_at < ? OR (created_at = ? AND id < ?))")
            arguments.add(createdAt)
            arguments.add(createdAt)
            arguments.add(decoded.id.toString())
        }
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT ?")
        arguments.add(limit + 1)
        val rows: List<AuditLog> = jdbc.query(
            sql.toString(),
            RowMapper { resultSet, _ ->
                AuditLog(
                    UUID.fromString(resultSet.getString("id")),
                    resultSet.getString("actor_username"),
                    resultSet.getString("action"),
                    resultSet.getString("resource_path"),
                    resultSet.getInt("outcome_status"),
                    resultSet.getString("remote_address"),
                    resultSet.getString("trace_id"),
                    UtcTimestampCodec.parse(resultSet.getString("created_at")),
                )
            },
            *arguments.toTypedArray(),
        )
        val hasMore = rows.size > limit
        val logs = if (hasMore) rows.take(limit) else rows.toList()
        return AuditLogPage(
            logs,
            if (hasMore) encodeCursor(logs.last()) else null,
        )
    }

    private fun encodeCursor(log: AuditLog): String {
        val raw = "${UtcTimestampCodec.format(log.createdAt)}|${log.id}"
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray(StandardCharsets.UTF_8))
    }

    private fun decodeCursor(encoded: String): Cursor = try {
        val raw = String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8)
        val separator = raw.indexOf('|')
        require(separator > 0 && separator != raw.lastIndex && raw.indexOf('|', separator + 1) < 0) {
            "cursor is invalid"
        }
        Cursor(
            UtcTimestampCodec.parse(raw.substring(0, separator)),
            UUID.fromString(raw.substring(separator + 1)),
        )
    } catch (exception: IllegalArgumentException) {
        throw IllegalArgumentException("cursor is invalid", exception)
    }

    private data class Cursor(val createdAt: Instant, val id: UUID)

    private companion object {
        const val COLUMNS =
            "id, actor_username, action, resource_path, outcome_status, remote_address, trace_id, created_at"
    }
}
