package com.mieai.qqbot.onebot11.mapping

import com.mieai.qqbot.client.QqMessageTargetType
import com.mieai.qqbot.domain.bot.BotId
import java.sql.ResultSet
import java.sql.Statement
import java.time.Clock
import javax.sql.DataSource
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.GeneratedKeyHolder

/** Message context required by OneBot int32 IDs, replies, lookup, and recall. */
class OneBotMessageRepository(
    dataSource: DataSource,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val jdbc = JdbcTemplate(dataSource)

    fun record(
        botId: BotId,
        officialMessageId: String,
        targetType: QqMessageTargetType,
        targetRawId: String,
        direction: OneBotStoredMessage.Direction,
        messageType: String,
        eventTime: Long,
        userId: Long,
        messageJson: String,
        senderJson: String,
    ): Int {
        validate(
            officialMessageId,
            targetType,
            targetRawId,
            messageType,
            eventTime,
            userId,
            messageJson,
            senderJson,
        )
        findByOfficial(botId, officialMessageId)?.let { return it.messageId }
        val keys = GeneratedKeyHolder()
        try {
            jdbc.update({ connection ->
                connection.prepareStatement(
                    """
                    INSERT INTO onebot11_messages (
                        bot_id, official_message_id, target_type, target_raw_id,
                        direction, message_type, event_time, user_id,
                        message_json, sender_json, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    Statement.RETURN_GENERATED_KEYS,
                ).apply {
                    setString(1, botId.toString())
                    setString(2, officialMessageId)
                    setString(3, targetType.name)
                    setString(4, targetRawId)
                    setString(5, direction.name)
                    setString(6, messageType)
                    setLong(7, eventTime)
                    setLong(8, userId)
                    setString(9, messageJson)
                    setString(10, senderJson)
                    setString(11, clock.instant().toString())
                }
            }, keys)
            keys.key?.let { key ->
                if (key.toLong() <= Int.MAX_VALUE) {
                    return key.toInt()
                }
            }
        } catch (_: DuplicateKeyException) {
            // A retried Gateway event may race with the original insert.
        }
        return findByOfficial(botId, officialMessageId)?.messageId
            ?: throw IllegalStateException("Message ID was not generated")
    }

    fun find(botId: BotId, messageId: Int): OneBotStoredMessage? = jdbc.query(
        "SELECT $COLUMNS FROM onebot11_messages WHERE bot_id=? AND message_id=?",
        { resultSet, _ -> map(resultSet) },
        botId.toString(),
        messageId,
    ).firstOrNull()

    fun findByOfficial(botId: BotId, officialMessageId: String): OneBotStoredMessage? =
        jdbc.query(
            "SELECT $COLUMNS FROM onebot11_messages WHERE bot_id=? AND official_message_id=?",
            { resultSet, _ -> map(resultSet) },
            botId.toString(),
            officialMessageId,
        ).firstOrNull()

    companion object {
        private val COLUMNS = """
            message_id, bot_id, official_message_id, target_type, target_raw_id,
            direction, message_type, event_time, user_id, message_json, sender_json
        """.trimIndent()

        private fun map(resultSet: ResultSet): OneBotStoredMessage = OneBotStoredMessage(
            resultSet.getInt("message_id"),
            BotId.parse(resultSet.getString("bot_id")),
            resultSet.getString("official_message_id"),
            QqMessageTargetType.valueOf(resultSet.getString("target_type")),
            resultSet.getString("target_raw_id"),
            OneBotStoredMessage.Direction.valueOf(resultSet.getString("direction")),
            resultSet.getString("message_type"),
            resultSet.getLong("event_time"),
            resultSet.getLong("user_id"),
            resultSet.getString("message_json"),
            resultSet.getString("sender_json"),
        )

        private fun validate(
            officialMessageId: String,
            targetType: QqMessageTargetType,
            targetRawId: String,
            messageType: String,
            eventTime: Long,
            userId: Long,
            messageJson: String,
            senderJson: String,
        ) {
            requireText(officialMessageId, "officialMessageId", 512)
            require(
                targetType == QqMessageTargetType.C2C || targetType == QqMessageTargetType.GROUP,
            ) { "Only C2C and group messages are supported" }
            requireText(targetRawId, "targetRawId", 512)
            require(messageType == "private" || messageType == "group") { "messageType is invalid" }
            require(eventTime >= 0L && userId >= 1L) { "message metadata is invalid" }
            requireText(messageJson, "messageJson", 1_000_000)
            requireText(senderJson, "senderJson", 1_000_000)
        }

        private fun requireText(value: String, name: String, maxLength: Int) {
            require(value.isNotBlank() && value.length <= maxLength) { "$name is invalid" }
        }
    }
}
