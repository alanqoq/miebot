package com.mieai.qqbot.persistence.lease

import com.mieai.qqbot.domain.bot.BotId
import com.mieai.qqbot.persistence.internal.DatabaseExceptionClassifier
import com.mieai.qqbot.persistence.internal.UtcTimestampCodec
import java.time.Duration
import java.time.Instant
import javax.sql.DataSource
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/** SQL-backed owner lease with a monotonically increasing fencing token. */
class JdbcBotLeaseRepository(dataSource: DataSource) : BotLeaseRepository {
    private val requiredDataSource = dataSource
    private val jdbc = JdbcTemplate(requiredDataSource)
    private val transaction = TransactionTemplate(DataSourceTransactionManager(requiredDataSource))

    override fun acquire(
        botId: BotId,
        shardIndex: Int,
        ownerId: String,
        now: Instant,
        duration: Duration,
    ): BotLease? {
        validate(shardIndex, ownerId, duration)
        val bot = botId.toString()
        val current = UtcTimestampCodec.format(now)
        val until = UtcTimestampCodec.format(now.plus(duration))
        return transaction.execute<BotLease> {
            val updated = jdbc.update(
                """
                UPDATE bot_leases SET owner_id=?, lease_until=?, fencing_token=fencing_token+1, updated_at=?
                WHERE bot_id=? AND shard_index=? AND (lease_until <= ? OR owner_id=?)
                  AND EXISTS (SELECT 1 FROM bots b WHERE b.id=? AND b.shard_index=?)
                """.trimIndent(),
                ownerId,
                until,
                current,
                bot,
                shardIndex,
                current,
                ownerId,
                bot,
                shardIndex,
            )
            if (updated == 1) {
                find(bot, shardIndex)
            } else {
                try {
                    val inserted = jdbc.update(
                        """
                        INSERT INTO bot_leases (bot_id, shard_index, owner_id, lease_until, fencing_token, updated_at)
                        SELECT b.id, b.shard_index, ?, ?, 1, ?
                        FROM bots b WHERE b.id=? AND b.shard_index=?
                        """.trimIndent(),
                        ownerId,
                        until,
                        current,
                        bot,
                        shardIndex,
                    )
                    if (inserted == 1) find(bot, shardIndex) else null
                } catch (duplicate: DataAccessException) {
                    // Another instance won the insert race; do not steal a live lease.
                    if (!DatabaseExceptionClassifier.isDuplicateKey(duplicate)) {
                        throw duplicate
                    }
                    null
                }
            }
        }
    }

    override fun acquire(
        botId: BotId,
        shardIndex: Int,
        ownerId: String,
        now: Instant,
        duration: Duration,
        pluginHashes: Map<String, String>,
    ): BotLease? {
        registerPluginHashes(ownerId, pluginHashes, now, duration)
        return if (pluginHashesMatch(botId, pluginHashes)) acquire(botId, shardIndex, ownerId, now, duration)
        else null
    }

    override fun isOwned(botId: BotId, shardIndex: Int, ownerId: String, now: Instant): Boolean {
        require(ownerId.isNotBlank()) { "ownerId is invalid" }
        val count = jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM bot_leases l
            JOIN bots b ON b.id=l.bot_id AND b.shard_index=l.shard_index
            WHERE l.bot_id=? AND l.shard_index=? AND l.owner_id=? AND l.lease_until > ?
            """.trimIndent(),
            Int::class.javaObjectType,
            botId.toString(),
            shardIndex,
            ownerId,
            UtcTimestampCodec.format(now),
        )
        return count != null && count == 1
    }

    override fun isOwned(botId: BotId, ownerId: String, now: Instant): Boolean {
        val count = jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM bot_leases l
            JOIN bots b ON b.id=l.bot_id AND b.shard_index=l.shard_index
            WHERE l.bot_id=? AND l.owner_id=? AND l.lease_until > ?
            """.trimIndent(),
            Int::class.javaObjectType,
            botId.toString(),
            ownerId,
            UtcTimestampCodec.format(now),
        )
        return count != null && count > 0
    }

    override fun registerPluginHashes(
        ownerId: String,
        pluginHashes: Map<String, String>,
        now: Instant,
        duration: Duration,
    ) {
        validateOwnerAndHashes(ownerId, pluginHashes)
        require(!duration.isZero && !duration.isNegative) { "duration must be positive" }
        val until = UtcTimestampCodec.format(now.plus(duration))
        pluginHashes.forEach { (pluginId, sha256) ->
            val updated = jdbc.update(
                """
                UPDATE instance_plugin_hashes SET sha256=?, lease_until=?, updated_at=?
                WHERE instance_id=? AND plugin_id=?
                """.trimIndent(),
                sha256,
                until,
                UtcTimestampCodec.format(now),
                ownerId,
                pluginId,
            )
            if (updated == 0) {
                jdbc.update(
                    """
                    INSERT INTO instance_plugin_hashes(instance_id, plugin_id, sha256, lease_until, updated_at)
                    VALUES (?, ?, ?, ?, ?)
                    """.trimIndent(),
                    ownerId,
                    pluginId,
                    sha256,
                    until,
                    UtcTimestampCodec.format(now),
                )
            }
        }
    }

    override fun unregisterPluginHashes(ownerId: String) {
        if (ownerId.isNotBlank()) {
            jdbc.update("DELETE FROM instance_plugin_hashes WHERE instance_id=?", ownerId)
        }
    }

    override fun renew(lease: BotLease, now: Instant, duration: Duration): Boolean {
        require(!duration.isZero && !duration.isNegative) { "duration must be positive" }
        return jdbc.update(
            """
            UPDATE bot_leases SET lease_until=?, updated_at=?
            WHERE bot_id=? AND shard_index=? AND owner_id=? AND fencing_token=? AND lease_until > ?
              AND EXISTS (SELECT 1 FROM bots b WHERE b.id=? AND b.shard_index=?)
            """.trimIndent(),
            UtcTimestampCodec.format(now.plus(duration)),
            UtcTimestampCodec.format(now),
            lease.botId.toString(),
            lease.shardIndex,
            lease.ownerId,
            lease.fencingToken,
            UtcTimestampCodec.format(now),
            lease.botId.toString(),
            lease.shardIndex,
        ) == 1
    }

    override fun renew(
        lease: BotLease,
        now: Instant,
        duration: Duration,
        pluginHashes: Map<String, String>,
    ): Boolean {
        registerPluginHashes(lease.ownerId, pluginHashes, now, duration)
        return pluginHashesMatch(lease.botId, pluginHashes) && renew(lease, now, duration)
    }

    override fun release(lease: BotLease): Boolean {
        return jdbc.update(
            "DELETE FROM bot_leases WHERE bot_id=? AND shard_index=? AND owner_id=? AND fencing_token=?",
            lease.botId.toString(),
            lease.shardIndex,
            lease.ownerId,
            lease.fencingToken,
        ) == 1
    }

    private fun find(botId: String, shardIndex: Int): BotLease? = jdbc.query(
        "SELECT bot_id, shard_index, owner_id, lease_until, fencing_token FROM bot_leases WHERE bot_id=? AND shard_index=?",
        RowMapper { resultSet, _ ->
            BotLease(
                BotId.parse(resultSet.getString("bot_id")),
                resultSet.getInt("shard_index"),
                resultSet.getString("owner_id"),
                UtcTimestampCodec.parse(resultSet.getString("lease_until")),
                resultSet.getLong("fencing_token"),
            )
        },
        botId,
        shardIndex,
    ).firstOrNull()

    private fun pluginHashesMatch(botId: BotId, local: Map<String, String>): Boolean {
        val required: List<Pair<String, String>> = jdbc.query(
            """
            SELECT a.plugin_id, a.sha256
            FROM bot_plugins b JOIN plugin_artifacts a ON a.plugin_id=b.plugin_id
            WHERE b.bot_id=? AND b.enabled=1 AND b.runtime_state='ACTIVE'
            """.trimIndent(),
            RowMapper { resultSet, _ -> resultSet.getString(1) to resultSet.getString(2) },
            botId.toString(),
        )
        return required.all { (pluginId, sha256) -> sha256 == local[pluginId] }
    }

    private fun validateOwnerAndHashes(ownerId: String, hashes: Map<String, String>) {
        require(
            ownerId.isNotBlank() && ownerId.length <= 255 &&
                ownerId.codePoints().noneMatch { Character.isWhitespace(it) },
        ) { "ownerId is invalid" }
        hashes.forEach { (plugin, hash) ->
            require(
                plugin.isNotBlank() && hash.isNotBlank() && hash.length <= 128 &&
                    hash.codePoints().noneMatch { Character.isWhitespace(it) },
            ) { "plugin hash registration is invalid" }
        }
    }

    private fun validate(shardIndex: Int, ownerId: String, duration: Duration) {
        require(shardIndex >= 0) { "shardIndex must not be negative" }
        require(
            ownerId.isNotBlank() && ownerId.length <= 255 &&
                ownerId.codePoints().noneMatch { Character.isWhitespace(it) } &&
                ownerId.codePoints().noneMatch { Character.isISOControl(it) },
        ) { "ownerId is invalid" }
        require(!duration.isZero && !duration.isNegative) { "duration must be positive" }
    }
}
