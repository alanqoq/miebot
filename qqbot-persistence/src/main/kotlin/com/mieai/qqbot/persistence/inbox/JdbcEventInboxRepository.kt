package com.mieai.qqbot.persistence.inbox

import com.mieai.qqbot.persistence.internal.DatabaseExceptionClassifier
import com.mieai.qqbot.persistence.internal.PersistenceValidation
import com.mieai.qqbot.persistence.internal.UtcTimestampCodec
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
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

/** JDBC Inbox repository using a database uniqueness constraint for deduplication. */
class JdbcEventInboxRepository(dataSource: DataSource) : EventInboxRepository {
    private val requiredDataSource = dataSource
    private val jdbc = JdbcTemplate(requiredDataSource)
    private val transaction = TransactionTemplate(DataSourceTransactionManager(requiredDataSource))

    override fun insertOrGet(event: IncomingEvent): InboxInsertResult {
        val receivedAt = UtcTimestampCodec.format(event.receivedAt)
        var inserted = false
        try {
            inserted = jdbc.update(
                """
                INSERT INTO event_inbox (
                    id, environment, bot_id, event_type, platform_event_id, payload,
                    status, attempt, available_at, lease_owner, lease_until,
                    fencing_token, last_error, received_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, 'RECEIVED', 0, ?, NULL, NULL, 0, NULL, ?, ?)
                """.trimIndent(),
                event.id.toString(),
                event.environment.name,
                event.botId.toString(),
                event.eventType,
                event.platformEventId,
                event.payload,
                receivedAt,
                receivedAt,
                receivedAt,
            ) == 1
        } catch (exception: DataAccessException) {
            if (!DatabaseExceptionClassifier.isDuplicateKey(exception)) {
                throw exception
            }
            // The unique event key is the cross-database deduplication boundary.
        }

        val stored = findByUniqueKey(event)
            ?: throw IllegalStateException("Inbox insert did not create or find an event")
        return InboxInsertResult(inserted, stored)
    }

    override fun findById(id: UUID): InboxEvent? {
        PersistenceValidation.requireIdentifier(id, "id")
        return jdbc.query(
            "SELECT $SELECT_COLUMNS FROM event_inbox WHERE id = ?",
            ROW_MAPPER,
            id.toString(),
        ).firstOrNull()
    }

    override fun query(query: InboxQuery): InboxPage {
        val sql = StringBuilder("SELECT ").append(LIST_SELECT_COLUMNS).append(" FROM event_inbox WHERE 1 = 1")
        val arguments = ArrayList<Any>()

        query.environment?.let { environment ->
            sql.append(" AND environment = ?")
            arguments.add(environment.name)
        }
        query.botId?.let { botId ->
            sql.append(" AND bot_id = ?")
            arguments.add(botId.toString())
        }
        query.status?.let { status ->
            sql.append(" AND status = ?")
            arguments.add(status.name)
        }
        query.eventType?.let { eventType ->
            sql.append(" AND event_type = ?")
            arguments.add(eventType)
        }
        query.search?.let { search ->
            val pattern = "%${escapeLike(search.lowercase(Locale.ROOT))}%"
            sql.append(
                " AND (LOWER(event_type) LIKE ? ESCAPE '!'" +
                    " OR LOWER(platform_event_id) LIKE ? ESCAPE '!')",
            )
            arguments.add(pattern)
            arguments.add(pattern)
        }
        query.cursor?.let { cursor ->
            val decoded = decodeCursor(cursor)
            val receivedAt = UtcTimestampCodec.format(decoded.receivedAt)
            sql.append(" AND (received_at < ? OR (received_at = ? AND id < ?))")
            arguments.add(receivedAt)
            arguments.add(receivedAt)
            arguments.add(decoded.id.toString())
        }

        sql.append(" ORDER BY received_at DESC, id DESC LIMIT ?")
        arguments.add(query.limit + 1)
        val rows = jdbc.query(sql.toString(), ROW_MAPPER, *arguments.toTypedArray())
        val hasMore = rows.size > query.limit
        val events = if (hasMore) rows.take(query.limit) else rows.toList()
        val nextCursor = if (hasMore) encodeCursor(events.last()) else null
        return InboxPage(events, nextCursor)
    }

    override fun claimNext(leaseOwner: String, now: Instant, leaseDuration: Duration): InboxEvent? =
        claimNextInternal(leaseOwner, null, now, leaseDuration)

    override fun claimNextOwned(
        leaseOwner: String,
        botLeaseOwner: String,
        now: Instant,
        leaseDuration: Duration,
    ): InboxEvent? {
        PersistenceValidation.requireToken(botLeaseOwner, "botLeaseOwner")
        return claimNextInternal(leaseOwner, botLeaseOwner, now, leaseDuration)
    }

    private fun claimNextInternal(
        leaseOwner: String,
        botLeaseOwner: String?,
        now: Instant,
        leaseDuration: Duration,
    ): InboxEvent? {
        PersistenceValidation.requireToken(leaseOwner, "leaseOwner")
        require(!leaseDuration.isZero && !leaseDuration.isNegative) { "leaseDuration must be positive" }
        val leaseUntil = now.plus(leaseDuration)
        val nowText = UtcTimestampCodec.format(now)
        return transaction.execute<InboxEvent> {
            val product = jdbc.execute(ConnectionCallback { connection ->
                connection.metaData.databaseProductName.lowercase(Locale.ROOT)
            })
            when {
                product?.contains("sqlite") == true -> if (botLeaseOwner == null) {
                    claimSQLite(leaseOwner, leaseUntil, nowText)
                } else {
                    claimSQLiteOwned(leaseOwner, botLeaseOwner, leaseUntil, nowText)
                }

                product?.contains("mysql") == true || product?.contains("postgresql") == true -> {
                    if (botLeaseOwner == null) {
                        claimLocked(leaseOwner, leaseUntil, nowText)
                    } else {
                        claimLockedOwned(leaseOwner, botLeaseOwner, leaseUntil, nowText)
                    }
                }

                else -> throw IllegalStateException("Unsupported database product: $product")
            }
        }
    }

    private fun claimSQLiteOwned(
        owner: String,
        botLeaseOwner: String,
        until: Instant,
        now: String,
    ): InboxEvent? {
        val claimed = jdbc.query(
            """
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
            RETURNING $SELECT_COLUMNS
            """.trimIndent(),
            InboxEventRowMapper(),
            now,
            now,
            botLeaseOwner,
            now,
            owner,
            UtcTimestampCodec.format(until),
            now,
        )
        check(claimed.size <= 1) { "Inbox claim returned more than one event" }
        return claimed.firstOrNull()
    }

    private fun claimLockedOwned(
        owner: String,
        botLeaseOwner: String,
        until: Instant,
        now: String,
    ): InboxEvent? {
        val candidates: List<String> = jdbc.query(
            """
            SELECT e.id FROM event_inbox e
            WHERE (((e.status='RECEIVED' AND e.available_at <= ?)
                OR (e.status='PROCESSING' AND e.lease_until <= ?))
                AND EXISTS (SELECT 1 FROM bot_leases l
                    JOIN bots configured
                      ON configured.id=l.bot_id AND configured.shard_index=l.shard_index
                    WHERE l.bot_id=e.bot_id AND l.owner_id=? AND l.lease_until > ?))
            ORDER BY CASE WHEN e.status='PROCESSING' THEN e.lease_until ELSE e.available_at END,
                e.received_at, e.id LIMIT 1 FOR UPDATE SKIP LOCKED
            """.trimIndent(),
            RowMapper { resultSet, _ -> resultSet.getString(1) },
            now,
            now,
            botLeaseOwner,
            now,
        )
        if (candidates.isEmpty()) return null
        val id = candidates.first()
        val updated = jdbc.update(
            """
            UPDATE event_inbox SET status='PROCESSING', attempt=attempt+1,
                lease_owner=?, lease_until=?, fencing_token=fencing_token+1, updated_at=?
            WHERE id=?
            """.trimIndent(),
            owner,
            UtcTimestampCodec.format(until),
            now,
            id,
        )
        check(updated == 1) { "Inbox claim affected $updated rows instead of one" }
        return jdbc.query(
            "SELECT $SELECT_COLUMNS FROM event_inbox WHERE id=?",
            InboxEventRowMapper(),
            id,
        ).firstOrNull()
    }

    private fun claimSQLite(owner: String, until: Instant, nowText: String): InboxEvent? {
        val claimed = jdbc.query(
            """
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
            RETURNING $SELECT_COLUMNS
            """.trimIndent(),
            InboxEventRowMapper(),
            nowText,
            nowText,
            owner,
            UtcTimestampCodec.format(until),
            nowText,
        )
        check(claimed.size <= 1) { "Inbox claim returned more than one event" }
        return claimed.firstOrNull()
    }

    private fun claimLocked(owner: String, until: Instant, nowText: String): InboxEvent? {
        val candidates: List<String> = jdbc.query(
            """
            SELECT id FROM event_inbox
            WHERE ((status = 'RECEIVED' AND available_at <= ?)
                OR (status = 'PROCESSING' AND lease_until <= ?))
            ORDER BY CASE WHEN status = 'PROCESSING' THEN lease_until ELSE available_at END,
                     received_at, id LIMIT 1 FOR UPDATE SKIP LOCKED
            """.trimIndent(),
            RowMapper { resultSet, _ -> resultSet.getString(1) },
            nowText,
            nowText,
        )
        if (candidates.isEmpty()) return null
        val id = candidates.first()
        val updated = jdbc.update(
            """
            UPDATE event_inbox
            SET status = 'PROCESSING', attempt = attempt + 1,
                lease_owner = ?, lease_until = ?, fencing_token = fencing_token + 1,
                updated_at = ?
            WHERE id = ?
            """.trimIndent(),
            owner,
            UtcTimestampCodec.format(until),
            nowText,
            id,
        )
        check(updated == 1) { "Inbox claim affected $updated rows instead of one" }
        return jdbc.query(
            "SELECT $SELECT_COLUMNS FROM event_inbox WHERE id = ?",
            InboxEventRowMapper(),
            id,
        ).firstOrNull()
    }

    override fun markDispatched(id: UUID, fencingToken: Long, now: Instant) {
        transition(id, fencingToken, now, InboxStatus.DISPATCHED, null, null)
    }

    override fun markRetry(id: UUID, fencingToken: Long, now: Instant, availableAt: Instant, error: String) {
        require(availableAt.isAfter(now)) { "availableAt must be after now" }
        transition(id, fencingToken, now, InboxStatus.RECEIVED, availableAt, error)
    }

    override fun markDeadLetter(id: UUID, fencingToken: Long, now: Instant, reason: String) {
        transition(id, fencingToken, now, InboxStatus.DEAD_LETTER, now, reason)
    }

    private fun transition(
        id: UUID,
        fencingToken: Long,
        now: Instant,
        target: InboxStatus,
        availableAt: Instant?,
        error: String?,
    ) {
        PersistenceValidation.requireIdentifier(id, "id")
        require(fencingToken >= 1L) { "fencingToken must be positive" }
        if (error != null) PersistenceValidation.requirePayload(error, "error")
        val available = UtcTimestampCodec.format(availableAt ?: now)
        val updated = jdbc.update(
            """
            UPDATE event_inbox
            SET status = ?, available_at = ?, lease_owner = NULL, lease_until = NULL,
                last_error = ?, updated_at = ?
            WHERE id = ? AND status = 'PROCESSING' AND fencing_token = ? AND lease_until > ?
            """.trimIndent(),
            target.name,
            available,
            error,
            UtcTimestampCodec.format(now),
            id.toString(),
            fencingToken,
            UtcTimestampCodec.format(now),
        )
        if (updated == 0) throw InboxTransitionException(id, fencingToken, target)
        check(updated == 1) { "Inbox transition affected $updated rows instead of one" }
    }

    private fun findByUniqueKey(event: IncomingEvent): InboxEvent? = jdbc.query(
        """
        SELECT $SELECT_COLUMNS
        FROM event_inbox
        WHERE environment = ? AND bot_id = ?
          AND event_type = ? AND platform_event_id = ?
        """.trimIndent(),
        ROW_MAPPER,
        event.environment.name,
        event.botId.toString(),
        event.eventType,
        event.platformEventId,
    ).firstOrNull()

    private fun encodeCursor(event: InboxEvent): String {
        val value = "${UtcTimestampCodec.format(event.receivedAt)}\n${event.id}"
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(StandardCharsets.UTF_8))
    }

    private fun decodeCursor(encoded: String): Cursor = try {
        val value = String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8)
        val separator = value.indexOf('\n')
        require(separator > 0 && separator != value.lastIndex && value.indexOf('\n', separator + 1) < 0) {
            "cursor must contain a timestamp and id"
        }
        Cursor(
            Instant.from(DateTimeFormatter.ISO_INSTANT.parse(value.substring(0, separator))),
            UUID.fromString(value.substring(separator + 1)),
        )
    } catch (exception: RuntimeException) {
        throw IllegalArgumentException("cursor is invalid", exception)
    }

    private fun escapeLike(value: String): String = value.replace("!", "!!").replace("%", "!%").replace("_", "!_")

    private data class Cursor(val receivedAt: Instant, val id: UUID)

    private companion object {
        const val SELECT_COLUMNS =
            "id, environment, bot_id, event_type, platform_event_id, payload, " +
                "status, attempt, available_at, lease_owner, lease_until, " +
                "fencing_token, last_error, received_at, updated_at"

        /** Listing never needs raw payloads; keep large Gateway frames out of the result set. */
        const val LIST_SELECT_COLUMNS =
            "id, environment, bot_id, event_type, platform_event_id, '_' AS payload, " +
                "status, attempt, available_at, lease_owner, lease_until, " +
                "fencing_token, SUBSTR(last_error, 1, 1024) AS last_error, received_at, updated_at"

        val ROW_MAPPER = InboxEventRowMapper()
    }
}
