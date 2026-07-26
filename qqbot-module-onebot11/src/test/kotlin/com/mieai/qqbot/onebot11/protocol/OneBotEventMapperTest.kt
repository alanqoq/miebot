package com.mieai.qqbot.onebot11.protocol

import com.fasterxml.jackson.databind.ObjectMapper
import com.mieai.qqbot.domain.bot.BotDefinition
import com.mieai.qqbot.domain.bot.BotId
import com.mieai.qqbot.domain.bot.QqAppId
import com.mieai.qqbot.gateway.GatewayDispatch
import com.mieai.qqbot.onebot11.mapping.OneBotEntityIdRepository
import com.mieai.qqbot.onebot11.mapping.OneBotMessageRepository
import com.mieai.qqbot.onebot11.support.OneBotTestDatabase
import com.mieai.qqbot.persistence.bot.BotRepository
import com.mieai.qqbot.persistence.bot.StoredBot
import com.mieai.qqbot.runtime.event.BotGatewayEvent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

class OneBotEventMapperTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `maps C2C and ordinary group messages but not channel messages`() {
        val botId = BotId.of(UUID.randomUUID())
        val dataSource = OneBotTestDatabase.create(directory.resolve("events.db"))
        OneBotTestDatabase.insertBot(dataSource, botId.toString())
        val bots = mock(BotRepository::class.java)
        val stored = mock(StoredBot::class.java)
        val definition = mock(BotDefinition::class.java)
        `when`(definition.appId).thenReturn(QqAppId.of("123456"))
        `when`(stored.definition).thenReturn(definition)
        `when`(bots.findById(botId)).thenReturn(stored)
        val objectMapper = ObjectMapper()
        val mapper = OneBotEventMapper(
            objectMapper,
            bots,
            OneBotEntityIdRepository(dataSource),
            OneBotMessageRepository(dataSource),
            OneBotMessageCodec(objectMapper),
        )

        val privateEvent = mapper.map(
            event(
                botId,
                "C2C_MESSAGE_CREATE",
                """
                {"op":0,"s":1,"t":"C2C_MESSAGE_CREATE","id":"event-1","d":{
                  "id":"message-1","content":"hello","timestamp":"2026-07-22T08:00:00Z",
                  "author":{"user_openid":"user-openid","username":"Alice"}
                }}
                """.trimIndent(),
            ),
        ) ?: error("Expected a private message event")
        assertThat(privateEvent.path("post_type").asText()).isEqualTo("message")
        assertThat(privateEvent.path("message_type").asText()).isEqualTo("private")
        assertThat(privateEvent.path("sender").path("nickname").asText()).isEqualTo("Alice")
        assertThat(privateEvent.path("message_id").asInt()).isPositive()

        val groupEvent = mapper.map(
            event(
                botId,
                "GROUP_AT_MESSAGE_CREATE",
                """
                {"op":0,"s":2,"t":"GROUP_AT_MESSAGE_CREATE","id":"event-2","d":{
                  "id":"message-2","group_openid":"group-openid","content":"group hello",
                  "timestamp":"2026-07-22T08:01:00Z",
                  "author":{"member_openid":"member-openid","username":"Bob"}
                }}
                """.trimIndent(),
            ),
        ) ?: error("Expected a group message event")
        assertThat(groupEvent.path("message_type").asText()).isEqualTo("group")
        assertThat(groupEvent.path("group_id").asLong()).isPositive()

        assertThat(
            mapper.map(
                event(
                    botId,
                    "AT_MESSAGE_CREATE",
                    """
                    {"op":0,"s":3,"t":"AT_MESSAGE_CREATE","id":"event-3","d":{
                      "id":"channel-message","channel_id":"channel","guild_id":"guild",
                      "content":"ignored","author":{"id":"guild-user"}
                    }}
                    """.trimIndent(),
                ),
            ),
        ).isNull()
    }

    private fun event(botId: BotId, type: String, payload: String): BotGatewayEvent =
        BotGatewayEvent(
            botId,
            GatewayDispatch(1L, type, payload),
            Instant.parse("2026-07-22T08:00:00Z"),
        )
}
