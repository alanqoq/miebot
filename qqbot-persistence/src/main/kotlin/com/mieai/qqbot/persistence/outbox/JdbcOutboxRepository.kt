package com.mieai.qqbot.persistence.outbox

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
import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/** JDBC Outbox repository with safe claim operations for all supported database dialects. */
class JdbcOutboxRepository(dataSource: DataSource) : OutboxRepository {
    private val requiredDataSource = dataSource
    private val jdbc = JdbcTemplate(requiredDataSource)
    private val transaction = TransactionTemplate(DataSourceTransactionManager(requiredDataSource))

    override fun create(job: NewOutboxJob) {
        val createdAt = UtcTimestampCodec.format(job.createdAt)
        val inserted = jdbc.update(
            """
            INSERT INTO outbox_jobs (
                id, environment, bot_id, source_event_id, job_type, dedup_key, payload,
                status, attempt, available_at, lease_owner, lease_until,
                fencing_token, last_error, created_at, updated_at, completed_at,
                producer_binding_id, platform_message_id, platform_message_sequence, platform_timestamp
            ) VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', 0, ?, NULL, NULL, 0, NULL, ?, ?, NULL,
                ?, NULL, NULL, NULL)
            """.trimIndent(),
            job.id.toString(),
            job.environment.name,
            job.botId.toString(),
            job.sourceEventId?.toString(),
            job.jobType,
            job.dedupKey,
            job.payload,
            UtcTimestampCodec.format(job.availableAt),
            createdAt,
            createdAt,
            job.producerBindingId?.toString(),
        )
        check(inserted == 1) { "Outbox insert affected $inserted rows instead of one" }
    }

    override fun findById(id: UUID): OutboxJob? {
        PersistenceValidation.requireIdentifier(id, "id")
        return jdbc.query(
            "SELECT $SELECT_COLUMNS FROM outbox_jobs WHERE id = ?",
            ROW_MAPPER,
            id.toString(),
        ).firstOrNull()
    }

    override fun findByIdAndProducerBindingId(id: UUID, producerBindingId: UUID): OutboxJob? {
        PersistenceValidation.requireIdentifier(id, "id")
        PersistenceValidation.requireIdentifier(producerBindingId, "producerBindingId")
        return jdbc.query(
            "SELECT $SELECT_COLUMNS FROM outbox_jobs WHERE id = ? AND producer_binding_id = ?",
            ROW_MAPPER,
            id.toString(),
            producerBindingId.toString(),
        ).firstOrNull()
    }

    override fun query(query: OutboxQuery): OutboxPage {
        val sql = StringBuilder("SELECT ").append(LIST_SELECT_COLUMNS).append(" FROM outbox_jobs WHERE 1 = 1")
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
        query.jobType?.let { jobType ->
            sql.append(" AND job_type = ?")
            arguments.add(jobType)
        }
        query.search?.let { search ->
            val pattern = "%${escapeLike(search.lowercase(Locale.ROOT))}%"
            sql.append(
                " AND (LOWER(id) LIKE ? ESCAPE '!'" +
                    " OR LOWER(bot_id) LIKE ? ESCAPE '!'" +
                    " OR LOWER(job_type) LIKE ? ESCAPE '!'" +
                    " OR LOWER(COALESCE(dedup_key, '')) LIKE ? ESCAPE '!'" +
                    " OR LOWER(COALESCE(source_event_id, '')) LIKE ? ESCAPE '!'" +
                    " OR LOWER(COALESCE(producer_binding_id, '')) LIKE ? ESCAPE '!'" +
                    " OR LOWER(COALESCE(platform_message_id, '')) LIKE ? ESCAPE '!'" +
                    " OR LOWER(COALESCE(last_error, '')) LIKE ? ESCAPE '!')",
            )
            repeat(8) { arguments.add(pattern) }
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
        val rows = jdbc.query(sql.toString(), ROW_MAPPER, *arguments.toTypedArray())
        val hasMore = rows.size > query.limit
        val jobs = if (hasMore) rows.take(query.limit) else rows.toList()
        val nextCursor = if (hasMore) encodeCursor(jobs.last()) else null
        return OutboxPage(jobs, nextCursor)
    }

    override fun statistics(): OutboxQueueStats = jdbc.queryForObject(
        """
        SELECT COUNT(*) AS total_count,
               COALESCE(SUM(CASE WHEN status = 'PENDING' THEN 1 ELSE 0 END), 0) AS pending_count,
               COALESCE(SUM(CASE WHEN status = 'IN_PROGRESS' THEN 1 ELSE 0 END), 0) AS in_progress_count,
               COALESCE(SUM(CASE WHEN status = 'RETRY_WAIT' THEN 1 ELSE 0 END), 0) AS retry_wait_count,
               COALESCE(SUM(CASE WHEN status = 'SUCCEEDED' THEN 1 ELSE 0 END), 0) AS succeeded_count,
               COALESCE(SUM(CASE WHEN status = 'RESULT_UNKNOWN' THEN 1 ELSE 0 END), 0) AS result_unknown_count,
               COALESCE(SUM(CASE WHEN status = 'DEAD_LETTER' THEN 1 ELSE 0 END), 0) AS dead_letter_count
        FROM outbox_jobs
        """.trimIndent(),
        RowMapper { resultSet, _ ->
            OutboxQueueStats(
                resultSet.getLong("total_count"),
                resultSet.getLong("pending_count"),
                resultSet.getLong("in_progress_count"),
                resultSet.getLong("retry_wait_count"),
                resultSet.getLong("succeeded_count"),
                resultSet.getLong("result_unknown_count"),
                resultSet.getLong("dead_letter_count"),
            )
        },
    ) ?: error("Outbox statistics query returned no row")

    override fun claimNext(leaseOwner: String, now: Instant, leaseDuration: Duration): OutboxJob? =
        claimNextInternal(leaseOwner, null, now, leaseDuration)

    override fun claimNextOwned(
        leaseOwner: String,
        botLeaseOwner: String,
        now: Instant,
        leaseDuration: Duration,
    ): OutboxJob? {
        PersistenceValidation.requireToken(botLeaseOwner, "botLeaseOwner")
        return claimNextInternal(leaseOwner, botLeaseOwner, now, leaseDuration)
    }

    private fun claimNextInternal(
        leaseOwner: String,
        botLeaseOwner: String?,
        now: Instant,
        leaseDuration: Duration,
    ): OutboxJob? {
        PersistenceValidation.requireToken(leaseOwner, "leaseOwner")
        require(!leaseDuration.isZero && !leaseDuration.isNegative) { "leaseDuration must be positive" }
        val leaseUntil = now.plus(leaseDuration)
        val nowText = UtcTimestampCodec.format(now)

        return transaction.execute<OutboxJob> {
            val databaseProduct = jdbc.execute(ConnectionCallback { connection ->
                connection.metaData.databaseProductName.lowercase(Locale.ROOT)
            })
            when {
                databaseProduct?.contains("sqlite") == true -> if (botLeaseOwner == null) {
                    claimSQLite(leaseOwner, leaseUntil, nowText)
                } else {
                    claimSQLiteOwned(leaseOwner, botLeaseOwner, leaseUntil, nowText)
                }

                databaseProduct?.contains("mysql") == true || databaseProduct?.contains("postgresql") == true -> {
                    if (botLeaseOwner == null) {
                        claimWithLockedCandidate(leaseOwner, leaseUntil, nowText)
                    } else {
                        claimWithLockedOwnedCandidate(leaseOwner, botLeaseOwner, leaseUntil, nowText)
                    }
                }

                else -> throw IllegalStateException("Unsupported database product: $databaseProduct")
            }
        }
    }

    override fun renewLease(
        id: UUID,
        leaseOwner: String,
        fencingToken: Long,
        now: Instant,
        leaseDuration: Duration,
    ): Boolean {
        requireTransitionArguments(id, fencingToken)
        PersistenceValidation.requireToken(leaseOwner, "leaseOwner")
        require(!leaseDuration.isZero && !leaseDuration.isNegative) { "leaseDuration must be positive" }
        return jdbc.update(
            """
            UPDATE outbox_jobs
            SET lease_until = ?, updated_at = ?
            WHERE id = ? AND status = 'IN_PROGRESS' AND lease_owner = ?
              AND fencing_token = ? AND lease_until > ?
            """.trimIndent(),
            UtcTimestampCodec.format(now.plus(leaseDuration)),
            UtcTimestampCodec.format(now),
            id.toString(),
            leaseOwner,
            fencingToken,
            UtcTimestampCodec.format(now),
        ) == 1
    }

    private fun claimSQLiteOwned(
        leaseOwner: String,
        botLeaseOwner: String,
        leaseUntil: Instant,
        nowText: String,
    ): OutboxJob? {
        val claimed = jdbc.query(
            """
            WITH candidate AS (
                SELECT j.id
                FROM outbox_jobs j
                WHERE (((j.status IN ('PENDING', 'RETRY_WAIT') AND j.available_at <= ?)
                    OR (j.status = 'IN_PROGRESS' AND j.lease_until <= ?))
                  AND EXISTS (SELECT 1 FROM bot_leases l
                      JOIN bots configured
                        ON configured.id=l.bot_id AND configured.shard_index=l.shard_index
                      WHERE l.bot_id=j.bot_id AND l.owner_id=? AND l.lease_until > ?))
                ORDER BY CASE WHEN j.status='IN_PROGRESS' THEN j.lease_until ELSE j.available_at END,
                    j.created_at, j.id
                LIMIT 1
            )
            UPDATE outbox_jobs
            SET status='IN_PROGRESS', attempt=attempt+1, lease_owner=?, lease_until=?,
                fencing_token=fencing_token+1, updated_at=?, completed_at=NULL
            WHERE id=(SELECT id FROM candidate)
            RETURNING $SELECT_COLUMNS
            """.trimIndent(),
            ROW_MAPPER,
            nowText,
            nowText,
            botLeaseOwner,
            nowText,
            leaseOwner,
            UtcTimestampCodec.format(leaseUntil),
            nowText,
        )
        check(claimed.size <= 1) { "Outbox claim returned more than one job" }
        return claimed.firstOrNull()
    }

    private fun claimWithLockedOwnedCandidate(
        leaseOwner: String,
        botLeaseOwner: String,
        leaseUntil: Instant,
        nowText: String,
    ): OutboxJob? {
        val candidates: List<String> = jdbc.query(
            """
            SELECT j.id
            FROM outbox_jobs j
            WHERE (((j.status IN ('PENDING', 'RETRY_WAIT') AND j.available_at <= ?)
                OR (j.status = 'IN_PROGRESS' AND j.lease_until <= ?))
              AND EXISTS (SELECT 1 FROM bot_leases l
                  JOIN bots configured
                    ON configured.id=l.bot_id AND configured.shard_index=l.shard_index
                  WHERE l.bot_id=j.bot_id AND l.owner_id=? AND l.lease_until > ?))
            ORDER BY CASE WHEN j.status='IN_PROGRESS' THEN j.lease_until ELSE j.available_at END,
                j.created_at, j.id
            LIMIT 1 FOR UPDATE SKIP LOCKED
            """.trimIndent(),
            RowMapper { resultSet, _ -> resultSet.getString(1) },
            nowText,
            nowText,
            botLeaseOwner,
            nowText,
        )
        if (candidates.isEmpty()) return null
        val id = candidates.first()
        val updated = jdbc.update(
            """
            UPDATE outbox_jobs
            SET status='IN_PROGRESS', attempt=attempt+1, lease_owner=?, lease_until=?,
                fencing_token=fencing_token+1, updated_at=?, completed_at=NULL
            WHERE id=?
            """.trimIndent(),
            leaseOwner,
            UtcTimestampCodec.format(leaseUntil),
            nowText,
            id,
        )
        check(updated == 1) { "Outbox claim affected $updated rows instead of one" }
        return findByRawId(id)
    }

    private fun claimSQLite(leaseOwner: String, leaseUntil: Instant, nowText: String): OutboxJob? {
        val claimed = jdbc.query(
            """
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
            RETURNING $SELECT_COLUMNS
            """.trimIndent(),
            ROW_MAPPER,
            nowText,
            nowText,
            leaseOwner,
            UtcTimestampCodec.format(leaseUntil),
            nowText,
        )
        check(claimed.size <= 1) { "Outbox claim returned more than one job" }
        return claimed.firstOrNull()
    }

    private fun claimWithLockedCandidate(
        leaseOwner: String,
        leaseUntil: Instant,
        nowText: String,
    ): OutboxJob? {
        val candidates: List<String> = jdbc.query(
            """
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
            """.trimIndent(),
            RowMapper { resultSet, _ -> resultSet.getString(1) },
            nowText,
            nowText,
        )
        if (candidates.isEmpty()) return null

        val id = candidates.first()
        val updated = jdbc.update(
            """
            UPDATE outbox_jobs
            SET status = 'IN_PROGRESS',
                attempt = attempt + 1,
                lease_owner = ?,
                lease_until = ?,
                fencing_token = fencing_token + 1,
                updated_at = ?,
                completed_at = NULL
            WHERE id = ?
            """.trimIndent(),
            leaseOwner,
            UtcTimestampCodec.format(leaseUntil),
            nowText,
            id,
        )
        check(updated == 1) { "Outbox claim affected $updated rows instead of one" }
        return findByRawId(id)
    }

    override fun markSucceeded(id: UUID, fencingToken: Long, now: Instant, receipt: OutboxSendReceipt) {
        requireTransitionArguments(id, fencingToken)
        val updated = jdbc.update(
            """
            UPDATE outbox_jobs
            SET status = 'SUCCEEDED',
                lease_owner = NULL,
                lease_until = NULL,
                last_error = NULL,
                updated_at = ?,
                completed_at = ?,
                platform_message_id = ?,
                platform_message_sequence = ?,
                platform_timestamp = ?
            WHERE id = ? AND status = 'IN_PROGRESS'
              AND fencing_token = ? AND lease_until > ?
            """.trimIndent(),
            UtcTimestampCodec.format(now),
            UtcTimestampCodec.format(now),
            receipt.platformMessageId,
            receipt.platformMessageSequence,
            receipt.platformTimestamp,
            id.toString(),
            fencingToken,
            UtcTimestampCodec.format(now),
        )
        requireTransition(updated, id, fencingToken, OutboxStatus.SUCCEEDED)
    }

    override fun markRetry(id: UUID, fencingToken: Long, now: Instant, availableAt: Instant, error: String) {
        requireTransitionArguments(id, fencingToken)
        PersistenceValidation.requirePayload(error, "error")
        require(availableAt.isAfter(now)) { "availableAt must be after now" }
        val updated = jdbc.update(
            """
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
            """.trimIndent(),
            UtcTimestampCodec.format(availableAt),
            error,
            UtcTimestampCodec.format(now),
            id.toString(),
            fencingToken,
            UtcTimestampCodec.format(now),
        )
        requireTransition(updated, id, fencingToken, OutboxStatus.RETRY_WAIT)
    }

    override fun markResultUnknown(id: UUID, fencingToken: Long, now: Instant, reason: String) {
        markTerminal(id, fencingToken, now, reason, OutboxStatus.RESULT_UNKNOWN)
    }

    override fun markDeadLetter(id: UUID, fencingToken: Long, now: Instant, reason: String) {
        markTerminal(id, fencingToken, now, reason, OutboxStatus.DEAD_LETTER)
    }

    private fun markTerminal(
        id: UUID,
        fencingToken: Long,
        now: Instant,
        reason: String,
        targetStatus: OutboxStatus,
    ) {
        requireTransitionArguments(id, fencingToken)
        PersistenceValidation.requirePayload(reason, "reason")
        val updated = jdbc.update(
            """
            UPDATE outbox_jobs
            SET status = ?,
                lease_owner = NULL,
                lease_until = NULL,
                last_error = ?,
                updated_at = ?,
                completed_at = ?
            WHERE id = ? AND status = 'IN_PROGRESS'
              AND fencing_token = ? AND lease_until > ?
            """.trimIndent(),
            targetStatus.name,
            reason,
            UtcTimestampCodec.format(now),
            UtcTimestampCodec.format(now),
            id.toString(),
            fencingToken,
            UtcTimestampCodec.format(now),
        )
        requireTransition(updated, id, fencingToken, targetStatus)
    }

    private fun requireTransitionArguments(id: UUID, fencingToken: Long) {
        PersistenceValidation.requireIdentifier(id, "id")
        require(fencingToken >= 1L) { "fencingToken must be positive" }
    }

    private fun requireTransition(
        affectedRows: Int,
        id: UUID,
        fencingToken: Long,
        targetStatus: OutboxStatus,
    ) {
        if (affectedRows == 0) throw OutboxTransitionException(id, fencingToken, targetStatus)
        check(affectedRows == 1) { "Outbox transition affected $affectedRows rows instead of one" }
    }

    private fun findByRawId(id: String): OutboxJob? = jdbc.query(
        "SELECT $SELECT_COLUMNS FROM outbox_jobs WHERE id = ?",
        ROW_MAPPER,
        id,
    ).firstOrNull()

    private fun encodeCursor(job: OutboxJob): String {
        val value = "${UtcTimestampCodec.format(job.createdAt)}\n${job.id}"
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(StandardCharsets.UTF_8))
    }

    private fun decodeCursor(encoded: String): Cursor = try {
        val value = String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8)
        val separator = value.indexOf('\n')
        require(separator > 0 && separator != value.lastIndex && value.indexOf('\n', separator + 1) < 0) {
            "cursor must contain a timestamp and id"
        }
        val rawId = value.substring(separator + 1)
        val id = UUID.fromString(rawId)
        PersistenceValidation.requireIdentifier(id, "cursor id")
        require(id.toString().equals(rawId, ignoreCase = true)) { "cursor id must use canonical UUID format" }
        Cursor(
            Instant.from(DateTimeFormatter.ISO_INSTANT.parse(value.substring(0, separator))),
            id,
        )
    } catch (exception: RuntimeException) {
        throw IllegalArgumentException("cursor is invalid", exception)
    }

    private fun escapeLike(value: String): String = value.replace("!", "!!").replace("%", "!%").replace("_", "!_")

    private data class Cursor(val createdAt: Instant, val id: UUID)

    private companion object {
        const val SELECT_COLUMNS =
            "id, environment, bot_id, source_event_id, job_type, dedup_key, payload, " +
                "status, attempt, available_at, lease_owner, lease_until, " +
                "fencing_token, last_error, created_at, updated_at, completed_at, " +
                "producer_binding_id, platform_message_id, platform_message_sequence, platform_timestamp"

        /** Listing never needs raw payloads; avoid transferring potentially huge message bodies. */
        const val LIST_SELECT_COLUMNS =
            "id, environment, bot_id, source_event_id, job_type, dedup_key, '_' AS payload, " +
                "status, attempt, available_at, lease_owner, lease_until, " +
                "fencing_token, SUBSTR(last_error, 1, 1024) AS last_error, " +
                "created_at, updated_at, completed_at, producer_binding_id, " +
                "platform_message_id, platform_message_sequence, platform_timestamp"

        val ROW_MAPPER = OutboxJobRowMapper()
    }
}
