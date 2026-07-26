package com.mieai.qqbot.onebot11.protocol

import com.fasterxml.jackson.databind.ObjectMapper
import com.mieai.qqbot.client.QqMessageSendResult
import com.mieai.qqbot.client.QqOpenApiClient
import com.mieai.qqbot.client.QqTextMessageRequest
import com.mieai.qqbot.domain.bot.BotDefinition
import com.mieai.qqbot.domain.bot.BotId
import com.mieai.qqbot.onebot11.mapping.OneBotEntityIdRepository
import com.mieai.qqbot.onebot11.mapping.OneBotEntityType
import com.mieai.qqbot.onebot11.mapping.OneBotMessageRepository
import com.mieai.qqbot.onebot11.support.OneBotTestDatabase
import com.mieai.qqbot.persistence.bot.BotRepository
import com.mieai.qqbot.persistence.bot.StoredBot
import com.mieai.qqbot.runtime.openapi.BotOpenApiClientProvider
import com.mieai.qqbot.runtime.supervisor.BotSupervisor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class OneBotActionServiceTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `sends mapped C2C message, preserves echo, and indexes result`() {
        val botId = BotId.of(UUID.randomUUID())
        val dataSource = OneBotTestDatabase.create(directory.resolve("actions.db"))
        OneBotTestDatabase.insertBot(dataSource, botId.toString())
        val entityIds = OneBotEntityIdRepository(dataSource)
        val messages = OneBotMessageRepository(dataSource)
        val userId = entityIds.aliasFor(botId, OneBotEntityType.USER, "", "user-openid")
        val mapper = ObjectMapper()

        val client = mock(QqOpenApiClient::class.java)
        val requestFallback = QqTextMessageRequest(
            com.mieai.qqbot.client.QqMessageTargetType.C2C,
            "fallback",
            "fallback",
            null,
            null,
            1,
        )
        `when`(client.sendText(anyValue(QqTextMessageRequest::class.java, requestFallback))).thenReturn(
            CompletableFuture.completedFuture(
                QqMessageSendResult("official-message", 1, "2026-07-22T08:00:00Z"),
            ),
        )
        val clients = mock(BotOpenApiClientProvider::class.java)
        `when`(clients.clientFor(botId)).thenReturn(client)
        val bots = mock(BotRepository::class.java)
        val storedBot = mock(StoredBot::class.java)
        val definition = mock(BotDefinition::class.java)
        `when`(definition.displayName).thenReturn("Test Bot")
        `when`(storedBot.definition).thenReturn(definition)
        `when`(bots.findById(botId)).thenReturn(storedBot)
        val events = mock(OneBotEventMapper::class.java)
        `when`(events.selfId(botId)).thenReturn(10L)

        OneBotActionService(
            mapper,
            clients,
            bots,
            mock(BotSupervisor::class.java),
            entityIds,
            messages,
            OneBotMessageCodec(mapper),
            OneBotMediaCache(directory.resolve("cache")),
            events,
        ).use { service ->
            val response = service.handle(
                botId,
                """
                    {"action":"send_private_msg","params":{
                      "user_id":$userId,"message":[{"type":"text","data":{"text":"hello"}}]
                    },"echo":{"request":7}}
                """.trimIndent(),
            ).toCompletableFuture().get(5, TimeUnit.SECONDS)
            val json = mapper.readTree(response)

            assertThat(json.path("status").asText()).isEqualTo("ok")
            assertThat(json.path("echo").path("request").asInt()).isEqualTo(7)
            val messageId = json.path("data").path("message_id").asInt()
            assertThat(messageId).isPositive()
            assertThat(requireNotNull(messages.find(botId, messageId)).officialMessageId)
                .isEqualTo("official-message")

            val request = ArgumentCaptor.forClass(QqTextMessageRequest::class.java)
            verify(client).sendText(request.capture() ?: requestFallback)
            assertThat(request.value.targetId).isEqualTo("user-openid")
            assertThat(request.value.content).isEqualTo("hello")
        }
    }

    @Test
    fun `returns explicit failure for unsupported actions`() {
        val mapper = ObjectMapper()
        OneBotActionService(
            mapper,
            mock(BotOpenApiClientProvider::class.java),
            mock(BotRepository::class.java),
            mock(BotSupervisor::class.java),
            mock(OneBotEntityIdRepository::class.java),
            mock(OneBotMessageRepository::class.java),
            OneBotMessageCodec(mapper),
            OneBotMediaCache(directory.resolve("cache")),
            mock(OneBotEventMapper::class.java),
        ).use { service ->
            listOf(
                "set_group_kick",
                "set_group_kick_async",
                "set_group_kick_rate_limited",
            ).forEach { action ->
                val response = service.handle(
                    BotId.of(UUID.randomUUID()),
                    """{"action":"$action","echo":"same"}""",
                ).toCompletableFuture().get(5, TimeUnit.SECONDS)
                val json = mapper.readTree(response)
                assertThat(json.path("status").asText()).describedAs(action).isEqualTo("failed")
                assertThat(json.path("retcode").asInt()).describedAs(action).isEqualTo(1404)
                assertThat(json.path("echo").asText()).describedAs(action).isEqualTo("same")
            }
        }
    }

    private fun <T : Any> anyValue(type: Class<T>, fallback: T): T =
        ArgumentMatchers.any(type) ?: fallback
}
