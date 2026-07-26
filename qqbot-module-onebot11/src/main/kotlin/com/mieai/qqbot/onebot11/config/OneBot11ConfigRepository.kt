package com.mieai.qqbot.onebot11.config

import com.mieai.qqbot.domain.bot.BotId
import com.mieai.qqbot.runtime.security.EncryptedConfigurationValue
import java.net.URI
import java.sql.ResultSet
import java.time.Instant
import javax.sql.DataSource
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper

class OneBot11ConfigRepository(dataSource: DataSource) {
    private val jdbc = JdbcTemplate(dataSource)

    fun find(botId: BotId): OneBot11Config? = jdbc.query(
        "SELECT $COLUMNS FROM onebot11_configs WHERE bot_id=?",
        ROW_MAPPER,
        botId.toString(),
    ).firstOrNull()

    fun findEnabled(): List<OneBot11Config> = jdbc.query(
        "SELECT $COLUMNS FROM onebot11_configs WHERE enabled=1 ORDER BY bot_id",
        ROW_MAPPER,
    )

    fun forwardPortUsedByOther(botId: BotId, port: Int): Boolean {
        val count = jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM onebot11_configs
            WHERE bot_id<>? AND enabled=1 AND forward_enabled=1 AND forward_port=?
            """.trimIndent(),
            Long::class.java,
            botId.toString(),
            port,
        )
        return count != null && count > 0L
    }

    fun save(value: OneBot11Config, expectedRevision: Long): OneBot11Config {
        require(expectedRevision >= 0L && value.revision == expectedRevision) {
            "expectedRevision is invalid"
        }
        val nextRevision = expectedRevision + 1L
        val now = value.updatedAt
        if (expectedRevision == 0L) {
            try {
                val inserted = jdbc.update(
                    """
                    INSERT INTO onebot11_configs (
                        bot_id, enabled, forward_enabled, forward_bind_address, forward_port,
                        reverse_enabled, reverse_url, access_token_ciphertext, access_token_key_id,
                        heartbeat_enabled, heartbeat_interval_ms, reconnect_interval_ms,
                        revision, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    *parameters(value, nextRevision, value.createdAt, now),
                )
                requireSingle(inserted)
            } catch (_: DuplicateKeyException) {
                throw OneBot11RevisionConflictException()
            }
        } else {
            val values = parameters(value, nextRevision, value.createdAt, now)
            val updated = jdbc.update(
                """
                UPDATE onebot11_configs SET
                    enabled=?, forward_enabled=?, forward_bind_address=?, forward_port=?,
                    reverse_enabled=?, reverse_url=?, access_token_ciphertext=?, access_token_key_id=?,
                    heartbeat_enabled=?, heartbeat_interval_ms=?, reconnect_interval_ms=?,
                    revision=?, updated_at=?
                WHERE bot_id=? AND revision=?
                """.trimIndent(),
                values[1],
                values[2],
                values[3],
                values[4],
                values[5],
                values[6],
                values[7],
                values[8],
                values[9],
                values[10],
                values[11],
                values[12],
                values[14],
                values[0],
                expectedRevision,
            )
            if (updated == 0) {
                throw OneBot11RevisionConflictException()
            }
            requireSingle(updated)
        }
        return find(value.botId)
            ?: throw IllegalStateException("Saved OneBot settings could not be reloaded")
    }

    companion object {
        private val COLUMNS = """
            bot_id, enabled, forward_enabled, forward_bind_address, forward_port,
            reverse_enabled, reverse_url, access_token_ciphertext, access_token_key_id,
            heartbeat_enabled, heartbeat_interval_ms, reconnect_interval_ms,
            revision, created_at, updated_at
        """.trimIndent()

        private val ROW_MAPPER = RowMapper<OneBot11Config> { resultSet, _ -> map(resultSet) }

        private fun parameters(
            value: OneBot11Config,
            revision: Long,
            createdAt: Instant,
            updatedAt: Instant,
        ): Array<Any?> {
            val token = value.encryptedAccessToken
            return arrayOf(
                value.botId.toString(),
                if (value.enabled) 1 else 0,
                if (value.forwardEnabled) 1 else 0,
                value.forwardBindAddress,
                value.forwardPort,
                if (value.reverseEnabled) 1 else 0,
                value.reverseUrl?.toString(),
                token?.ciphertext,
                token?.keyId,
                if (value.heartbeatEnabled) 1 else 0,
                value.heartbeatIntervalMs,
                value.reconnectIntervalMs,
                revision,
                createdAt.toString(),
                updatedAt.toString(),
            )
        }

        private fun map(resultSet: ResultSet): OneBot11Config {
            val port = resultSet.getInt("forward_port")
            val forwardPort = if (resultSet.wasNull()) null else port
            val reverseUrl = resultSet.getString("reverse_url")
            val ciphertext = resultSet.getString("access_token_ciphertext")
            val keyId = resultSet.getString("access_token_key_id")
            val token = ciphertext?.let { EncryptedConfigurationValue(it, keyId) }
            return OneBot11Config(
                BotId.parse(resultSet.getString("bot_id")),
                resultSet.getInt("enabled") != 0,
                resultSet.getInt("forward_enabled") != 0,
                resultSet.getString("forward_bind_address"),
                forwardPort,
                resultSet.getInt("reverse_enabled") != 0,
                reverseUrl?.let(URI::create),
                token,
                resultSet.getInt("heartbeat_enabled") != 0,
                resultSet.getInt("heartbeat_interval_ms"),
                resultSet.getInt("reconnect_interval_ms"),
                resultSet.getLong("revision"),
                Instant.parse(resultSet.getString("created_at")),
                Instant.parse(resultSet.getString("updated_at")),
            )
        }

        private fun requireSingle(affected: Int) {
            check(affected == 1) { "OneBot settings write affected $affected rows" }
        }
    }
}
