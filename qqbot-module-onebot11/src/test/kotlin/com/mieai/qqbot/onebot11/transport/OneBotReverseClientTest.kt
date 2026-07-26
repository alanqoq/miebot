package com.mieai.qqbot.onebot11.transport

import com.mieai.qqbot.domain.bot.BotId
import com.mieai.qqbot.onebot11.protocol.OneBotActionService
import org.assertj.core.api.Assertions.assertThat
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.net.InetSocketAddress
import java.net.URI
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class OneBotReverseClientTest {
    @Test
    fun `connects as universal client with required headers`() {
        val serverStarted = CountDownLatch(1)
        val messagesReceived = CountDownLatch(2)
        val received = CopyOnWriteArrayList<String>()
        val headers = arrayOfNulls<String>(3)
        val server = object : WebSocketServer(InetSocketAddress("127.0.0.1", 0)) {
            override fun onOpen(connection: WebSocket, handshake: ClientHandshake) {
                headers[0] = handshake.getFieldValue("Authorization")
                headers[1] = handshake.getFieldValue("X-Self-ID")
                headers[2] = handshake.getFieldValue("X-Client-Role")
                connection.send("""{"action":"get_version_info"}""")
            }

            override fun onClose(connection: WebSocket, code: Int, reason: String, remote: Boolean) = Unit

            override fun onMessage(connection: WebSocket, message: String) {
                received.add(message)
                messagesReceived.countDown()
            }

            override fun onError(connection: WebSocket?, exception: Exception) = Unit

            override fun onStart() {
                serverStarted.countDown()
            }
        }
        server.isDaemon = true
        server.start()
        assertThat(serverStarted.await(5, TimeUnit.SECONDS)).isTrue()

        val botId = BotId.of(UUID.randomUUID())
        val actions = mock(OneBotActionService::class.java)
        `when`(actions.handle(botId, """{"action":"get_version_info"}"""))
            .thenReturn(CompletableFuture.completedFuture("""{"status":"ok"}"""))
        val client = OneBotReverseClient(
            botId,
            URI.create("ws://127.0.0.1:${server.port}/onebot"),
            42L,
            "reverse-secret",
            500,
            actions,
            { """{"post_type":"meta_event"}""" },
            {},
            {},
        )
        try {
            client.startConnecting()
            assertThat(messagesReceived.await(5, TimeUnit.SECONDS)).isTrue()
            assertThat(headers).containsExactly("Bearer reverse-secret", "42", "Universal")
            assertThat(received).contains("""{"post_type":"meta_event"}""", """{"status":"ok"}""")
        } finally {
            client.close()
            server.stop(3_000)
        }
    }
}
