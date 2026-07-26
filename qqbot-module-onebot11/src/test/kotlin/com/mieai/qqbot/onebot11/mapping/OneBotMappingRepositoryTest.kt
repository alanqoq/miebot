package com.mieai.qqbot.onebot11.mapping

import com.mieai.qqbot.client.QqMessageTargetType
import com.mieai.qqbot.domain.bot.BotId
import com.mieai.qqbot.onebot11.support.OneBotTestDatabase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID

class OneBotMappingRepositoryTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `keeps entity aliases stable and scopes group users`() {
        val botId = BotId.of(UUID.randomUUID())
        val dataSource = OneBotTestDatabase.create(directory.resolve("mapping.db"))
        OneBotTestDatabase.insertBot(dataSource, botId.toString())
        val ids = OneBotEntityIdRepository(dataSource)

        val privateUser = ids.aliasFor(botId, OneBotEntityType.USER, "", "openid-1")
        val samePrivateUser = ids.aliasFor(botId, OneBotEntityType.USER, "", "openid-1")
        val groupUser = ids.aliasFor(botId, OneBotEntityType.USER, "group-1", "openid-1")

        assertThat(samePrivateUser).isEqualTo(privateUser)
        assertThat(groupUser).isNotEqualTo(privateUser)
        assertThat(ids.require(botId, groupUser, OneBotEntityType.USER).scopeId)
            .isEqualTo("group-1")
    }

    @Test
    fun `stores int32 message context for lookup and recall`() {
        val botId = BotId.of(UUID.randomUUID())
        val dataSource = OneBotTestDatabase.create(directory.resolve("messages.db"))
        OneBotTestDatabase.insertBot(dataSource, botId.toString())
        val messages = OneBotMessageRepository(dataSource)

        val messageId = messages.record(
            botId,
            "qq-message-1",
            QqMessageTargetType.GROUP,
            "group-openid",
            OneBotStoredMessage.Direction.INCOMING,
            "group",
            1_721_632_000L,
            42L,
            """[{"type":"text","data":{"text":"hello"}}]""",
            """{"user_id":42,"nickname":"Alice"}""",
        )

        assertThat(messageId).isPositive()
        assertThat(requireNotNull(messages.find(botId, messageId)))
            .extracting(OneBotStoredMessage::officialMessageId, OneBotStoredMessage::targetRawId)
            .containsExactly("qq-message-1", "group-openid")
        assertThat(
            messages.record(
                botId,
                "qq-message-1",
                QqMessageTargetType.GROUP,
                "group-openid",
                OneBotStoredMessage.Direction.INCOMING,
                "group",
                1_721_632_000L,
                42L,
                "[]",
                "{}",
            ),
        ).isEqualTo(messageId)
    }
}
