package com.mieai.qqbot.onebot11.transport

import com.mieai.qqbot.domain.bot.BotId
import com.mieai.qqbot.onebot11.protocol.OneBotActionService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.java_websocket.client.WebSocketClient
import org.java_websocket.drafts.Draft_6455
import org.java_websocket.exceptions.InvalidDataException
import org.java_websocket.handshake.ServerHandshake
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.net.URI
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class OneBotForwardServerTest {
    @Test
    fun `accepts universal connections and returns action responses`() {
        val botId = BotId.of(UUID.randomUUID())
        val actions = mock(OneBotActionService::class.java)
        `when`(actions.handle(botId, """{"action":"get_status"}"""))
            .thenReturn(CompletableFuture.completedFuture("""{"status":"ok"}"""))
        val server = OneBotForwardServer(
            botId,
            "127.0.0.1",
            0,
            "secret",
            actions,
            { """{"post_type":"meta_event"}""" },
            {},
            {},
        )
        server.startListening()
        val received = CopyOnWriteArrayList<String>()
        val messages = CountDownLatch(2)
        val client = object : WebSocketClient(
            URI.create("ws://127.0.0.1:${server.port}/"),
            Draft_6455(),
            mapOf("Authorization" to "Bearer secret"),
            5_000,
        ) {
            override fun onOpen(handshake: ServerHandshake) {
                send("""{"action":"get_status"}""")
            }

            override fun onMessage(message: String) {
                received.add(message)
                messages.countDown()
            }

            override fun onClose(code: Int, reason: String, remote: Boolean) = Unit

            override fun onError(exception: Exception) = Unit
        }
        try {
            assertThat(client.connectBlocking(5, TimeUnit.SECONDS)).isTrue()
            assertThat(messages.await(5, TimeUnit.SECONDS)).isTrue()
            assertThat(received).containsExactly(
                """{"post_type":"meta_event"}""",
                """{"status":"ok"}""",
            )
        } finally {
            client.closeBlocking()
            server.close()
        }
    }

    @Test
    fun `rejects invalid bearer tokens during handshake`() {
        val server = OneBotForwardServer(
            BotId.of(UUID.randomUUID()),
            "127.0.0.1",
            0,
            "expected",
            mock(OneBotActionService::class.java),
            { "{}" },
            {},
            {},
        )
        val handshake = mock(org.java_websocket.handshake.ClientHandshake::class.java)
        `when`(handshake.resourceDescriptor).thenReturn("/api")
        `when`(handshake.getFieldValue("Authorization")).thenReturn("Bearer wrong")

        assertThatThrownBy {
            server.onWebsocketHandshakeReceivedAsServer(
                mock(org.java_websocket.WebSocket::class.java),
                Draft_6455(),
                handshake,
            )
        }.isInstanceOf(InvalidDataException::class.java)
    }
}
