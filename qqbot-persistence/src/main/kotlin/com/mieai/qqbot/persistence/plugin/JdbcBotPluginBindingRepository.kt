package com.mieai.qqbot.persistence.plugin

import com.mieai.qqbot.domain.bot.BotId
import com.mieai.qqbot.persistence.internal.UtcTimestampCodec
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import org.springframework.jdbc.core.JdbcTemplate

class JdbcBotPluginBindingRepository(dataSource: DataSource) : BotPluginBindingRepository {
    private val jdbc = JdbcTemplate(dataSource)

    override fun findAll(): List<BotPluginBinding> =
        jdbc.query(
            "SELECT $COLUMNS FROM bot_plugins ORDER BY created_at, id",
            BotPluginBindingRowMapper(),
        ).toList()

    override fun findEnabled(): List<BotPluginBinding> =
        jdbc.query(
            "SELECT $COLUMNS FROM bot_plugins WHERE enabled = 1 ORDER BY created_at, id",
            BotPluginBindingRowMapper(),
        ).toList()

    override fun findByBotId(botId: BotId): List<BotPluginBinding> {
        return jdbc.query(
            "SELECT $COLUMNS FROM bot_plugins WHERE bot_id = ? ORDER BY created_at, id",
            BotPluginBindingRowMapper(),
            botId.toString(),
        ).toList()
    }

    override fun findById(id: UUID): BotPluginBinding? = jdbc.query(
        "SELECT $COLUMNS FROM bot_plugins WHERE id = ?",
        BotPluginBindingRowMapper(),
        id.toString(),
    ).firstOrNull()

    override fun findByPluginAndBot(pluginId: String, botId: BotId): BotPluginBinding? = jdbc.query(
        "SELECT $COLUMNS FROM bot_plugins WHERE plugin_id = ? AND bot_id = ?",
        BotPluginBindingRowMapper(),
        pluginId,
        botId.toString(),
    ).firstOrNull()

    override fun insert(binding: BotPluginBinding) {
        jdbc.update(
            """
            INSERT INTO bot_plugins (id, plugin_id, bot_id, enabled, revision, runtime_state, runtime_error, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            binding.id.toString(),
            binding.pluginId,
            binding.botId.toString(),
            if (binding.enabled) 1 else 0,
            binding.revision,
            binding.runtimeState.name,
            binding.runtimeError,
            UtcTimestampCodec.format(binding.createdAt),
            UtcTimestampCodec.format(binding.updatedAt),
        )
    }

    override fun update(binding: BotPluginBinding, expectedRevision: Long): BotPluginBinding {
        require(binding.revision == expectedRevision) { "binding revision mismatch" }
        val next = Math.addExact(expectedRevision, 1L)
        val updated = jdbc.update(
            """
            UPDATE bot_plugins SET enabled=?, revision=?, runtime_state=?, runtime_error=?, updated_at=?
            WHERE id=? AND revision=?
            """.trimIndent(),
            if (binding.enabled) 1 else 0,
            next,
            binding.runtimeState.name,
            binding.runtimeError,
            UtcTimestampCodec.format(binding.updatedAt),
            binding.id.toString(),
            expectedRevision,
        )
        if (updated == 0) throw PluginBindingOptimisticLockException(binding.id, expectedRevision)
        return BotPluginBinding(
            binding.id,
            binding.pluginId,
            binding.botId,
            binding.enabled,
            next,
            binding.createdAt,
            binding.updatedAt,
            binding.runtimeState,
            binding.runtimeError,
        )
    }

    override fun touch(id: UUID, expectedRevision: Long, now: Instant): BotPluginBinding {
        require(expectedRevision >= 0L) { "expectedRevision must not be negative" }
        val next = Math.addExact(expectedRevision, 1L)
        val updated = jdbc.update(
            "UPDATE bot_plugins SET revision=?, updated_at=? WHERE id=? AND revision=?",
            next,
            UtcTimestampCodec.format(now),
            id.toString(),
            expectedRevision,
        )
        if (updated == 0) throw PluginBindingOptimisticLockException(id, expectedRevision)
        return findById(id) ?: throw PluginBindingOptimisticLockException(id, expectedRevision)
    }

    override fun setRuntimeState(id: UUID, state: PluginBindingRuntimeState, error: String?, now: Instant) {
        jdbc.update(
            "UPDATE bot_plugins SET runtime_state=?, runtime_error=?, updated_at=? WHERE id=?",
            state.name,
            error,
            UtcTimestampCodec.format(now),
            id.toString(),
        )
    }

    override fun delete(id: UUID) {
        jdbc.update("DELETE FROM bot_plugins WHERE id = ?", id.toString())
    }

    private companion object {
        const val COLUMNS =
            "id, plugin_id, bot_id, enabled, revision, runtime_state, runtime_error, created_at, updated_at"
    }
}
