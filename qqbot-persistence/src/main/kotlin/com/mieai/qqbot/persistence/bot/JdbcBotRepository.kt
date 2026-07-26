package com.mieai.qqbot.persistence.bot

import com.mieai.qqbot.domain.bot.BotDefinition
import com.mieai.qqbot.domain.bot.BotId
import com.mieai.qqbot.domain.bot.BotRevision
import javax.sql.DataSource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/** Spring JDBC implementation that keeps SQL and row mapping private to persistence. */
class JdbcBotRepository(dataSource: DataSource) : BotRepository {
    private val requiredDataSource = dataSource
    private val jdbc = JdbcTemplate(requiredDataSource)
    private val transaction = TransactionTemplate(DataSourceTransactionManager(requiredDataSource))

    override fun findById(id: BotId): StoredBot? {
        return jdbc.query(
            "SELECT $SELECT_COLUMNS FROM bots WHERE id = ?",
            ROW_MAPPER,
            id.toString(),
        ).firstOrNull()
    }

    override fun findAll(): List<StoredBot> =
        jdbc.query(
            "SELECT $SELECT_COLUMNS FROM bots ORDER BY created_at, id",
            ROW_MAPPER,
        ).toList()

    override fun findEnabled(): List<StoredBot> =
        jdbc.query(
            "SELECT $SELECT_COLUMNS FROM bots WHERE enabled = 1 ORDER BY created_at, id",
            ROW_MAPPER,
        ).toList()

    override fun insert(bot: StoredBot) {
        val definition = bot.definition
        val secret = bot.appSecret
        val inserted = jdbc.update(
            """
            INSERT INTO bots (
                id, display_name, app_id, environment,
                app_secret_ciphertext, app_secret_key_id,
                intents, shard_index, shard_count, max_media_upload_bytes, enabled, revision,
                created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            definition.id.toString(),
            definition.displayName,
            definition.appId.value,
            definition.environment.name,
            secret.ciphertext,
            secret.keyId,
            definition.intents.bits,
            definition.shardSpec.index,
            definition.shardSpec.count,
            definition.maxMediaUploadBytes,
            if (definition.enabled) 1 else 0,
            definition.revision.value,
            definition.createdAt.toString(),
            definition.updatedAt.toString(),
        )
        requireSingleRow(inserted, "insert")
    }

    override fun update(bot: StoredBot, expectedRevision: BotRevision): BotRevision {
        val definition: BotDefinition = bot.definition
        require(definition.revision == expectedRevision) { "bot revision must equal expectedRevision" }

        val nextRevision = expectedRevision.next()
        val secret = bot.appSecret
        val updated = jdbc.update(
            """
            UPDATE bots
            SET display_name = ?,
                app_id = ?,
                environment = ?,
                app_secret_ciphertext = ?,
                app_secret_key_id = ?,
                intents = ?,
                shard_index = ?,
                shard_count = ?,
                max_media_upload_bytes = ?,
                enabled = ?,
                revision = ?,
                updated_at = ?
            WHERE id = ? AND revision = ?
            """.trimIndent(),
            definition.displayName,
            definition.appId.value,
            definition.environment.name,
            secret.ciphertext,
            secret.keyId,
            definition.intents.bits,
            definition.shardSpec.index,
            definition.shardSpec.count,
            definition.maxMediaUploadBytes,
            if (definition.enabled) 1 else 0,
            nextRevision.value,
            definition.updatedAt.toString(),
            definition.id.toString(),
            expectedRevision.value,
        )
        if (updated == 0) {
            throw OptimisticLockException(definition.id, expectedRevision)
        }
        requireSingleRow(updated, "update")
        return nextRevision
    }

    override fun delete(id: BotId): Boolean {
        return transaction.execute<Boolean> {
            val value = id.toString()
            jdbc.update(
                "DELETE FROM plugin_deliveries WHERE binding_id IN (SELECT id FROM bot_plugins WHERE bot_id=?)",
                value,
            )
            jdbc.update("DELETE FROM bot_plugins WHERE bot_id=?", value)
            jdbc.update("DELETE FROM outbox_jobs WHERE bot_id=?", value)
            jdbc.update("DELETE FROM event_inbox WHERE bot_id=?", value)
            jdbc.update("DELETE FROM bots WHERE id=?", value) == 1
        } == true
    }

    private fun requireSingleRow(affectedRows: Int, operation: String) {
        check(affectedRows == 1) { "$operation affected $affectedRows rows instead of one" }
    }

    private companion object {
        const val SELECT_COLUMNS =
            "id, display_name, app_id, environment, " +
                "app_secret_ciphertext, app_secret_key_id, " +
                "intents, shard_index, shard_count, max_media_upload_bytes, enabled, revision, " +
                "created_at, updated_at"
        val ROW_MAPPER = StoredBotRowMapper()
    }
}
