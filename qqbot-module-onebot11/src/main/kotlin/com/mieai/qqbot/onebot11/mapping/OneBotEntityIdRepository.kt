package com.mieai.qqbot.onebot11.mapping

import com.mieai.qqbot.domain.bot.BotId
import java.sql.Statement
import java.time.Clock
import javax.sql.DataSource
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.GeneratedKeyHolder

/** Stable numeric aliases for QQ string identifiers. */
class OneBotEntityIdRepository(
    dataSource: DataSource,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val jdbc = JdbcTemplate(dataSource)

    fun aliasFor(botId: BotId, type: OneBotEntityType, scopeId: String, rawId: String): Long {
        requireValues(scopeId, rawId)
        findAlias(botId, type, scopeId, rawId)?.let { return it }
        val keys = GeneratedKeyHolder()
        try {
            jdbc.update({ connection ->
                connection.prepareStatement(
                    """
                    INSERT INTO onebot11_entity_ids (
                        bot_id, entity_type, scope_id, raw_id, created_at
                    ) VALUES (?, ?, ?, ?, ?)
                    """.trimIndent(),
                    Statement.RETURN_GENERATED_KEYS,
                ).apply {
                    setString(1, botId.toString())
                    setString(2, type.name)
                    setString(3, scopeId)
                    setString(4, rawId)
                    setString(5, clock.instant().toString())
                }
            }, keys)
            keys.key?.let { return it.toLong() }
        } catch (_: DuplicateKeyException) {
            // A concurrent event inserted the same identity; load its stable alias below.
        }
        return findAlias(botId, type, scopeId, rawId)
            ?: throw IllegalStateException("Entity alias was not generated")
    }

    fun find(botId: BotId, oneBotId: Long): OneBotEntityMapping? {
        if (oneBotId < 1L) {
            return null
        }
        val matches = jdbc.query(
            """
            SELECT onebot_id, entity_type, scope_id, raw_id
            FROM onebot11_entity_ids WHERE bot_id=? AND onebot_id=?
            """.trimIndent(),
            { resultSet, _ ->
                OneBotEntityMapping(
                    resultSet.getLong("onebot_id"),
                    OneBotEntityType.valueOf(resultSet.getString("entity_type")),
                    resultSet.getString("scope_id"),
                    resultSet.getString("raw_id"),
                )
            },
            botId.toString(),
            oneBotId,
        )
        return matches.firstOrNull()
    }

    fun require(botId: BotId, oneBotId: Long, expectedType: OneBotEntityType): OneBotEntityMapping {
        val mapping = find(botId, oneBotId)
            ?: throw IllegalArgumentException("Unknown OneBot identifier")
        require(mapping.type == expectedType) { "OneBot identifier has the wrong type" }
        return mapping
    }

    private fun findAlias(
        botId: BotId,
        type: OneBotEntityType,
        scopeId: String,
        rawId: String,
    ): Long? = jdbc.query(
        """
        SELECT onebot_id FROM onebot11_entity_ids
        WHERE bot_id=? AND entity_type=? AND scope_id=? AND raw_id=?
        """.trimIndent(),
        { resultSet, _ -> resultSet.getLong(1) },
        botId.toString(),
        type.name,
        scopeId,
        rawId,
    ).firstOrNull()

    companion object {
        private fun requireValues(scopeId: String, rawId: String) {
            requireToken(scopeId, "scopeId", true)
            requireToken(rawId, "rawId", false)
        }

        private fun requireToken(value: String, name: String, emptyAllowed: Boolean) {
            require(
                (emptyAllowed || value.isNotBlank()) &&
                    value == value.trim() &&
                    value.length <= 512 &&
                    value.codePoints().noneMatch { Character.isISOControl(it) },
            ) { "$name is invalid" }
        }
    }
}
