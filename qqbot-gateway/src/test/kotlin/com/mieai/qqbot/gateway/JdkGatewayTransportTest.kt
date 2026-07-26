package com.mieai.qqbot.gateway

import java.net.URI
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class JdkGatewayTransportTest {
    @Test
    fun buffersCallbacksUntilConnectedAndCombinesFragmentedTextInWireOrder() {
        val connector = FakeConnector()
        val listener = RecordingListener()
        val transport = JdkGatewayTransport(connector, 128)

        val connecting = transport.connect(GATEWAY_URL, listener)
        connector.listener.onOpen(connector.socket)
        connector.listener.onText(connector.socket, "{\"op\":", false)
        connector.listener.onText(connector.socket, "10}", true)

        assertThat(listener.events).isEmpty()
        connector.opening.complete(connector.socket)
        val connection = connecting.toCompletableFuture().join()

        assertThat(listener.events).containsExactly("text:{\"op\":10}")
        assertThat(connector.socket.requested).isEqualTo(3L)

        connection.sendText("identify").toCompletableFuture().join()
        connection.close().toCompletableFuture().join()
        assertThat(connector.socket.sentTexts).containsExactly("identify")
        assertThat(connector.socket.closeCodes).containsExactly(WebSocket.NORMAL_CLOSURE)
    }

    @Test
    fun rejectsOversizedAndBinaryMessagesWithOneTerminalCallback() {
        val connector = FakeConnector()
        val listener = RecordingListener()
        var transport = JdkGatewayTransport(connector, 4)

        transport.connect(GATEWAY_URL, listener)
        connector.opening.complete(connector.socket)
        connector.listener.onText(connector.socket, "12345", true)
        connector.listener.onError(connector.socket, IllegalStateException("duplicate"))
        connector.listener.onClose(connector.socket, 1006, "duplicate")

        assertThat(listener.events).containsExactly("failure:IllegalArgumentException")
        assertThat(connector.socket.aborted).isTrue()

        val binaryConnector = FakeConnector()
        val binaryListener = RecordingListener()
        transport = JdkGatewayTransport(binaryConnector, 4)
        transport.connect(GATEWAY_URL, binaryListener)
        binaryConnector.opening.complete(binaryConnector.socket)
        binaryConnector.listener.onBinary(binaryConnector.socket, ByteBuffer.wrap(byteArrayOf(1)), true)

        assertThat(binaryListener.events).containsExactly("failure:IllegalArgumentException")
        assertThat(binaryConnector.socket.aborted).isTrue()
    }

    @Test
    fun reportsRemoteCloseAndDoesNotPublishHandshakeFailure() {
        val connector = FakeConnector()
        val listener = RecordingListener()
        var transport = JdkGatewayTransport(connector, 128)
        val connecting = transport.connect(GATEWAY_URL, listener)

        connector.opening.completeExceptionally(IllegalStateException("handshake failed"))

        assertThatThrownBy { connecting.toCompletableFuture().join() }.hasRootCauseMessage("handshake failed")
        assertThat(listener.events).isEmpty()

        val closeConnector = FakeConnector()
        val closeListener = RecordingListener()
        transport = JdkGatewayTransport(closeConnector, 128)
        transport.connect(GATEWAY_URL, closeListener)
        closeConnector.opening.complete(closeConnector.socket)
        closeConnector.listener.onClose(closeConnector.socket, 4009, "session timeout")
        closeConnector.listener.onError(closeConnector.socket, IllegalStateException("late"))

        assertThat(closeListener.events).containsExactly("closed:4009")
    }

    private class FakeConnector : JdkGatewayTransport.WebSocketConnector {
        val opening = CompletableFuture<WebSocket>()
        val socket = FakeWebSocket()
        lateinit var listener: WebSocket.Listener

        override fun connect(uri: URI, listener: WebSocket.Listener): CompletionStage<WebSocket> {
            assertThat(uri).isSameAs(GATEWAY_URL)
            this.listener = listener
            return opening
        }
    }

    private class RecordingListener : GatewayTransport.Listener {
        val events = mutableListOf<String>()
        override fun onText(payload: String) { events += "text:$payload" }
        override fun onClosed(statusCode: Int, reason: String) { events += "closed:$statusCode" }
        override fun onFailure(cause: Throwable) { events += "failure:${cause.javaClass.simpleName}" }
    }

    private class FakeWebSocket : WebSocket {
        val sentTexts = mutableListOf<String>()
        val closeCodes = mutableListOf<Int>()
        var requested = 0L
        var aborted = false

        override fun sendText(data: CharSequence, last: Boolean): CompletableFuture<WebSocket> =
            CompletableFuture.completedFuture(this.also { sentTexts += data.toString() })
        override fun sendBinary(data: ByteBuffer, last: Boolean): CompletableFuture<WebSocket> = CompletableFuture.completedFuture(this)
        override fun sendPing(message: ByteBuffer): CompletableFuture<WebSocket> = CompletableFuture.completedFuture(this)
        override fun sendPong(message: ByteBuffer): CompletableFuture<WebSocket> = CompletableFuture.completedFuture(this)
        override fun sendClose(statusCode: Int, reason: String): CompletableFuture<WebSocket> =
            CompletableFuture.completedFuture(this.also { closeCodes += statusCode })
        override fun request(n: Long) { requested += n }
        override fun getSubprotocol(): String = ""
        override fun isOutputClosed(): Boolean = false
        override fun isInputClosed(): Boolean = false
        override fun abort() { aborted = true }
    }

    private companion object {
        val GATEWAY_URL: URI = URI.create("wss://gateway.example/ws")
    }
}
