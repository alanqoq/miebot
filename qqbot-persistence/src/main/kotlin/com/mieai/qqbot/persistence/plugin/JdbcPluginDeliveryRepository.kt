package com.mieai.qqbot.persistence.plugin

import com.mieai.qqbot.persistence.internal.PersistenceValidation
import com.mieai.qqbot.persistence.internal.UtcTimestampCodec
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.ArrayList
import java.util.Base64
import java.util.Locale
import java.util.UUID
import javax.sql.DataSource
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/** Fenced JDBC queue for event-to-plugin deliveries. */
class JdbcPluginDeliveryRepository(dataSource: DataSource) : PluginDeliveryRepository {
    private val requiredDataSource = dataSource
    private val jdbc = JdbcTemplate(requiredDataSource)
    private val transaction = TransactionTemplate(DataSourceTransactionManager(requiredDataSource))

    override fun createIfAbsent(
        id: UUID,
        eventId: UUID,
        bindingId: UUID,
        handlerId: String,
        now: Instant,
    ): Boolean {
        PersistenceValidation.requireIdentifier(id, "id")
        PersistenceValidation.requireIdentifier(eventId, "eventId")
        PersistenceValidation.requireIdentifier(bindingId, "bindingId")
        PersistenceValidation.requireToken(handlerId, "handlerId", 128)
        val text = UtcTimestampCodec.format(now)
        return try {
            jdbc.update(
                """
                INSERT INTO plugin_deliveries (id, event_id, binding_id, handler_id, status, attempt,
                    available_at, lease_owner, lease_until, fencing_token, last_error, created_at, updated_at, completed_at)
                VALUES (?, ?, ?, ?, 'PENDING', 0, ?, NULL, NULL, 0, NULL, ?, ?, NULL)
                """.trimIndent(),
                id.toString(),
                eventId.toString(),
                bindingId.toString(),
                handlerId,
                text,
                text,
                text,
            ) == 1
        } catch (duplicateOrFailure: DataAccessException) {
            // A uniqueness violation means another worker already materialized this delivery.
            val existing: List<String> = jdbc.query(
                "SELECT id FROM plugin_deliveries WHERE event_id=? AND binding_id=? AND handler_id=?",
                RowMapper { resultSet, _ -> resultSet.getString(1) },
                eventId.toString(),
                bindingId.toString(),
                handlerId,
            )
            if (existing.isNotEmpty()) false else throw duplicateOrFailure
        }
    }

    override fun findById(id: UUID): PluginDelivery? {
        PersistenceValidation.requireIdentifier(id, "id")
        return jdbc.query(
            "SELECT $COLUMNS FROM plugin_deliveries WHERE id=?",
            PluginDeliveryRowMapper(),
            id.toString(),
        ).firstOrNull()
    }

    override fun query(query: PluginDeliveryQuery): PluginDeliveryPage {
        val sql = StringBuilder("SELECT ").append(COLUMNS).append(" FROM plugin_deliveries WHERE 1=1")
        val arguments = ArrayList<Any>()
        query.bindingId?.let { bindingId ->
            sql.append(" AND binding_id=?")
            arguments.add(bindingId.toString())
        }
        query.status?.let { status ->
            sql.append(" AND status=?")
            arguments.add(status.name)
        }
        query.search?.let { search ->
            val pattern = "%${escapeLike(search.lowercase(Locale.ROOT))}%"
            sql.append(
                " AND (LOWER(id) LIKE ? ESCAPE '!' OR LOWER(event_id) LIKE ? ESCAPE '!'" +
                    " OR LOWER(binding_id) LIKE ? ESCAPE '!' OR LOWER(handler_id) LIKE ? ESCAPE '!'" +
                    " OR LOWER(COALESCE(last_error,'')) LIKE ? ESCAPE '!')",
            )
            repeat(5) { arguments.add(pattern) }
        }
        query.cursor?.let { cursor ->
            val decoded = decodeCursor(cursor)
            val createdAt = UtcTimestampCodec.format(decoded.createdAt)
            sql.append(" AND (created_at < ? OR (created_at = ? AND id < ?))")
            arguments.add(createdAt)
            arguments.add(createdAt)
            arguments.add(decoded.id.toString())
        }
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT ?")
        arguments.add(query.limit + 1)
        val rows = jdbc.query(sql.toString(), PluginDeliveryRowMapper(), *arguments.toTypedArray())
        val hasMore = rows.size > query.limit
        val deliveries = if (hasMore) rows.take(query.limit) else rows.toList()
        val nextCursor = if (hasMore) encodeCursor(deliveries.last()) else null
        return PluginDeliveryPage(deliveries, nextCursor)
    }

    override fun statistics(): PluginDeliveryQueueStats = jdbc.queryForObject(
        """
        SELECT COUNT(*) AS total_count,
            COALESCE(SUM(CASE WHEN status='PENDING' THEN 1 ELSE 0 END),0) AS pending_count,
            COALESCE(SUM(CASE WHEN status='IN_PROGRESS' THEN 1 ELSE 0 END),0) AS in_progress_count,
            COALESCE(SUM(CASE WHEN status='RETRY_WAIT' THEN 1 ELSE 0 END),0) AS retry_wait_count,
            COALESCE(SUM(CASE WHEN status='SUCCEEDED' THEN 1 ELSE 0 END),0) AS succeeded_count,
            COALESCE(SUM(CASE WHEN status='DEAD_LETTER' THEN 1 ELSE 0 END),0) AS dead_letter_count,
            COALESCE(SUM(CASE WHEN status='PAUSED' THEN 1 ELSE 0 END),0) AS paused_count
        FROM plugin_deliveries
        """.trimIndent(),
        RowMapper { resultSet, _ ->
            PluginDeliveryQueueStats(
                resultSet.getLong("total_count"),
                resultSet.getLong("pending_count"),
                resultSet.getLong("in_progress_count"),
                resultSet.getLong("retry_wait_count"),
                resultSet.getLong("succeeded_count"),
                resultSet.getLong("dead_letter_count"),
                resultSet.getLong("paused_count"),
            )
        },
    ) ?: error("Plugin delivery statistics query returned no row")

    override fun claimNext(leaseOwner: String, now: Instant, leaseDuration: Duration): PluginDelivery? =
        claimNextInternal(leaseOwner, null, now, leaseDuration)

    override fun claimNextOwned(
        leaseOwner: String,
        botLeaseOwner: String,
        now: Instant,
        leaseDuration: Duration,
    ): PluginDelivery? {
        PersistenceValidation.requireToken(botLeaseOwner, "botLeaseOwner", 255)
        return claimNextInternal(leaseOwner, botLeaseOwner, now, leaseDuration)
    }

    private fun claimNextInternal(
        leaseOwner: String,
        botLeaseOwner: String?,
        now: Instant,
        leaseDuration: Duration,
    ): PluginDelivery? {
        PersistenceValidation.requireToken(leaseOwner, "leaseOwner", 255)
        require(!leaseDuration.isZero && !leaseDuration.isNegative) { "leaseDuration must be positive" }
        val until = now.plus(leaseDuration)
        val text = UtcTimestampCodec.format(now)
        return transaction.execute<PluginDelivery> {
            val product = jdbc.execute(ConnectionCallback { connection ->
                connection.metaData.databaseProductName.lowercase(Locale.ROOT)
            })
            when {
                product?.contains("sqlite") == true -> if (botLeaseOwner == null) {
                    claimSqlite(leaseOwner, until, text)
                } else {
                    claimSqliteOwned(leaseOwner, botLeaseOwner, until, text)
                }

                product?.contains("mysql") == true || product?.contains("postgresql") == true -> {
                    if (botLeaseOwner == null) {
                        claimLocked(leaseOwner, until, text)
                    } else {
                        claimLockedOwned(leaseOwner, botLeaseOwner, until, text)
                    }
                }

                else -> throw IllegalStateException("Unsupported database product: $product")
            }
        }
    }

    private fun claimSqliteOwned(
        owner: String,
        botLeaseOwner: String,
        until: Instant,
        now: String,
    ): PluginDelivery? {
        val rows = jdbc.query(
            """
            WITH candidate AS (
                SELECT d.id FROM plugin_deliveries d
                JOIN bot_plugins b ON b.id=d.binding_id
                WHERE (((d.status IN ('PENDING','RETRY_WAIT') AND d.available_at <= ?)
                    OR (d.status='IN_PROGRESS' AND d.lease_until <= ?))
                    AND b.enabled=1 AND b.runtime_state='ACTIVE'
                    AND EXISTS (SELECT 1 FROM bot_leases l
                        JOIN bots configured
                          ON configured.id=l.bot_id AND configured.shard_index=l.shard_index
                        WHERE l.bot_id=b.bot_id AND l.owner_id=? AND l.lease_until > ?))
                ORDER BY CASE WHEN d.status='IN_PROGRESS' THEN d.lease_until ELSE d.available_at END,
                    d.created_at, d.id LIMIT 1
            )
            UPDATE plugin_deliveries
            SET status='IN_PROGRESS', attempt=attempt+1, lease_owner=?, lease_until=?,
                fencing_token=fencing_token+1, updated_at=?, completed_at=NULL
            WHERE id=(SELECT id FROM candidate)
            RETURNING $COLUMNS
            """.trimIndent(),
            PluginDeliveryRowMapper(),
            now,
            now,
            botLeaseOwner,
            now,
            owner,
            UtcTimestampCodec.format(until),
            now,
        )
        check(rows.size <= 1) { "Plugin delivery claim returned more than one row" }
        return rows.firstOrNull()
    }

    private fun claimLockedOwned(
        owner: String,
        botLeaseOwner: String,
        until: Instant,
        now: String,
    ): PluginDelivery? {
        val ids: List<String> = jdbc.query(
            """
            SELECT d.id FROM plugin_deliveries d
            JOIN bot_plugins b ON b.id=d.binding_id
            WHERE (((d.status IN ('PENDING','RETRY_WAIT') AND d.available_at <= ?)
                OR (d.status='IN_PROGRESS' AND d.lease_until <= ?))
                AND b.enabled=1 AND b.runtime_state='ACTIVE'
                AND EXISTS (SELECT 1 FROM bot_leases l
                    JOIN bots configured
                      ON configured.id=l.bot_id AND configured.shard_index=l.shard_index
                    WHERE l.bot_id=b.bot_id AND l.owner_id=? AND l.lease_until > ?))
            ORDER BY CASE WHEN d.status='IN_PROGRESS' THEN d.lease_until ELSE d.available_at END,
                d.created_at, d.id LIMIT 1 FOR UPDATE SKIP LOCKED
            """.trimIndent(),
            RowMapper { resultSet, _ -> resultSet.getString(1) },
            now,
            now,
            botLeaseOwner,
            now,
        )
        if (ids.isEmpty()) return null
        val id = ids.first()
        val updated = jdbc.update(
            """
            UPDATE plugin_deliveries SET status='IN_PROGRESS', attempt=attempt+1,
                lease_owner=?, lease_until=?, fencing_token=fencing_token+1, updated_at=?, completed_at=NULL
            WHERE id=?
            """.trimIndent(),
            owner,
            UtcTimestampCodec.format(until),
            now,
            id,
        )
        check(updated == 1) { "Plugin delivery claim affected $updated rows instead of one" }
        return findByRawId(id)
    }

    private fun claimSqlite(owner: String, until: Instant, now: String): PluginDelivery? {
        val rows = jdbc.query(
            """
            WITH candidate AS (
                SELECT id FROM plugin_deliveries
                WHERE (((status IN ('PENDING','RETRY_WAIT') AND available_at <= ?)
                    OR (status = 'IN_PROGRESS' AND lease_until <= ?))
                    AND EXISTS (SELECT 1 FROM bot_plugins b WHERE b.id = binding_id
                        AND b.enabled = 1 AND b.runtime_state = 'ACTIVE'))
                ORDER BY CASE WHEN status='IN_PROGRESS' THEN lease_until ELSE available_at END, created_at, id LIMIT 1
            )
            UPDATE plugin_deliveries
            SET status='IN_PROGRESS', attempt=attempt+1, lease_owner=?, lease_until=?, fencing_token=fencing_token+1,
                updated_at=?, completed_at=NULL
            WHERE id=(SELECT id FROM candidate)
            RETURNING $COLUMNS
            """.trimIndent(),
            PluginDeliveryRowMapper(),
            now,
            now,
            owner,
            UtcTimestampCodec.format(until),
            now,
        )
        check(rows.size <= 1) { "Plugin delivery claim returned more than one row" }
        return rows.firstOrNull()
    }

    private fun claimLocked(owner: String, until: Instant, now: String): PluginDelivery? {
        val ids: List<String> = jdbc.query(
            """
            SELECT id FROM plugin_deliveries
            WHERE (((status IN ('PENDING','RETRY_WAIT') AND available_at <= ?)
                OR (status = 'IN_PROGRESS' AND lease_until <= ?))
                AND EXISTS (SELECT 1 FROM bot_plugins b WHERE b.id = binding_id
                    AND b.enabled = 1 AND b.runtime_state = 'ACTIVE'))
            ORDER BY CASE WHEN status='IN_PROGRESS' THEN lease_until ELSE available_at END, created_at, id
            LIMIT 1 FOR UPDATE SKIP LOCKED
            """.trimIndent(),
            RowMapper { resultSet, _ -> resultSet.getString(1) },
            now,
            now,
        )
        if (ids.isEmpty()) return null
        val id = ids.first()
        val updated = jdbc.update(
            """
            UPDATE plugin_deliveries SET status='IN_PROGRESS', attempt=attempt+1, lease_owner=?, lease_until=?,
                fencing_token=fencing_token+1, updated_at=?, completed_at=NULL WHERE id=?
            """.trimIndent(),
            owner,
            UtcTimestampCodec.format(until),
            now,
            id,
        )
        check(updated == 1) { "Plugin delivery claim affected $updated rows instead of one" }
        return findByRawId(id)
    }

    override fun markSucceeded(id: UUID, fencingToken: Long, now: Instant) {
        transition(id, fencingToken, now, PluginDeliveryStatus.SUCCEEDED, now, null, null)
    }

    override fun markRetry(id: UUID, fencingToken: Long, now: Instant, availableAt: Instant, error: String) {
        require(availableAt.isAfter(now)) { "availableAt must be after now" }
        transition(id, fencingToken, now, PluginDeliveryStatus.RETRY_WAIT, null, availableAt, error)
    }

    override fun markDeadLetter(id: UUID, fencingToken: Long, now: Instant, reason: String) {
        transition(id, fencingToken, now, PluginDeliveryStatus.DEAD_LETTER, now, now, reason)
    }

    override fun pauseForBinding(bindingId: UUID, now: Instant, reason: String): Int {
        PersistenceValidation.requireIdentifier(bindingId, "bindingId")
        PersistenceValidation.requirePayload(reason, "reason")
        return jdbc.update(
            """
            UPDATE plugin_deliveries
            SET status='PAUSED', lease_owner=NULL, lease_until=NULL, last_error=?, updated_at=?, completed_at=NULL
            WHERE binding_id=? AND status IN ('PENDING','RETRY_WAIT','IN_PROGRESS')
            """.trimIndent(),
            reason,
            UtcTimestampCodec.format(now),
            bindingId.toString(),
        )
    }

    override fun resumeForBinding(bindingId: UUID, now: Instant): Int {
        PersistenceValidation.requireIdentifier(bindingId, "bindingId")
        return jdbc.update(
            """
            UPDATE plugin_deliveries
            SET status='PENDING', available_at=?, last_error=NULL, updated_at=?
            WHERE binding_id=? AND status='PAUSED'
            """.trimIndent(),
            UtcTimestampCodec.format(now),
            UtcTimestampCodec.format(now),
            bindingId.toString(),
        )
    }

    private fun transition(
        id: UUID,
        fencingToken: Long,
        now: Instant,
        target: PluginDeliveryStatus,
        completed: Instant?,
        availableAt: Instant?,
        error: String?,
    ) {
        PersistenceValidation.requireIdentifier(id, "id")
        require(fencingToken >= 1L) { "fencingToken must be positive" }
        if (error != null) PersistenceValidation.requirePayload(error, "error")
        val available = UtcTimestampCodec.format(availableAt ?: now)
        val completedText = completed?.let(UtcTimestampCodec::format)
        val updated = jdbc.update(
            """
            UPDATE plugin_deliveries SET status=?, available_at=?, lease_owner=NULL, lease_until=NULL,
                last_error=?, updated_at=?, completed_at=?
            WHERE id=? AND status='IN_PROGRESS' AND fencing_token=? AND lease_until > ?
            """.trimIndent(),
            target.name,
            available,
            error,
            UtcTimestampCodec.format(now),
            completedText,
            id.toString(),
            fencingToken,
            UtcTimestampCodec.format(now),
        )
        if (updated == 0) throw PluginDeliveryTransitionException(id, fencingToken, target)
        check(updated == 1) { "Plugin delivery transition affected $updated rows instead of one" }
    }

    private fun findByRawId(id: String): PluginDelivery? = jdbc.query(
        "SELECT $COLUMNS FROM plugin_deliveries WHERE id=?",
        PluginDeliveryRowMapper(),
        id,
    ).firstOrNull()

    private fun encodeCursor(delivery: PluginDelivery): String {
        val raw = "${UtcTimestampCodec.format(delivery.createdAt)}|${delivery.id}"
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray(StandardCharsets.UTF_8))
    }

    private fun decodeCursor(encoded: String): Cursor = try {
        val raw = String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8)
        val separator = raw.indexOf('|')
        require(separator > 0 && separator != raw.lastIndex && raw.indexOf('|', separator + 1) < 0) {
            "cursor must contain a timestamp and id"
        }
        Cursor(
            UtcTimestampCodec.parse(raw.substring(0, separator)),
            UUID.fromString(raw.substring(separator + 1)),
        )
    } catch (exception: IllegalArgumentException) {
        throw IllegalArgumentException("cursor is invalid", exception)
    }

    private fun escapeLike(value: String): String = value.replace("!", "!!").replace("%", "!%").replace("_", "!_")

    private data class Cursor(val createdAt: Instant, val id: UUID)

    private companion object {
        const val COLUMNS =
            "id, event_id, binding_id, handler_id, status, attempt, available_at, lease_owner, lease_until, " +
                "fencing_token, last_error, created_at, updated_at, completed_at"
    }
}
