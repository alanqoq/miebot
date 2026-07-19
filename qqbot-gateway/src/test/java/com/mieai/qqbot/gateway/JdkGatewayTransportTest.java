package com.mieai.qqbot.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

class JdkGatewayTransportTest {
    private static final URI GATEWAY_URL = URI.create("wss://gateway.example/ws");

    @Test
    void buffersCallbacksUntilConnectedAndCombinesFragmentedTextInWireOrder() {
        FakeConnector connector = new FakeConnector();
        RecordingListener listener = new RecordingListener();
        JdkGatewayTransport transport = new JdkGatewayTransport(connector, 128);

        CompletionStage<GatewayConnection> connecting = transport.connect(GATEWAY_URL, listener);
        connector.listener.onOpen(connector.socket);
        connector.listener.onText(connector.socket, "{\"op\":", false);
        connector.listener.onText(connector.socket, "10}", true);

        assertThat(listener.events).isEmpty();
        connector.opening.complete(connector.socket);
        GatewayConnection connection = connecting.toCompletableFuture().join();

        assertThat(listener.events).containsExactly("text:{\"op\":10}");
        assertThat(connector.socket.requested).isEqualTo(3L);

        connection.sendText("identify").toCompletableFuture().join();
        connection.close().toCompletableFuture().join();
        assertThat(connector.socket.sentTexts).containsExactly("identify");
        assertThat(connector.socket.closeCodes).containsExactly(WebSocket.NORMAL_CLOSURE);
    }

    @Test
    void rejectsOversizedAndBinaryMessagesWithOneTerminalCallback() {
        FakeConnector connector = new FakeConnector();
        RecordingListener listener = new RecordingListener();
        JdkGatewayTransport transport = new JdkGatewayTransport(connector, 4);

        transport.connect(GATEWAY_URL, listener);
        connector.opening.complete(connector.socket);
        connector.listener.onText(connector.socket, "12345", true);
        connector.listener.onError(connector.socket, new IllegalStateException("duplicate"));
        connector.listener.onClose(connector.socket, 1006, "duplicate");

        assertThat(listener.events).containsExactly("failure:IllegalArgumentException");
        assertThat(connector.socket.aborted).isTrue();

        FakeConnector binaryConnector = new FakeConnector();
        RecordingListener binaryListener = new RecordingListener();
        transport = new JdkGatewayTransport(binaryConnector, 4);
        transport.connect(GATEWAY_URL, binaryListener);
        binaryConnector.opening.complete(binaryConnector.socket);
        binaryConnector.listener.onBinary(
                binaryConnector.socket, ByteBuffer.wrap(new byte[] {1}), true);

        assertThat(binaryListener.events).containsExactly("failure:IllegalArgumentException");
        assertThat(binaryConnector.socket.aborted).isTrue();
    }

    @Test
    void reportsRemoteCloseAndDoesNotPublishHandshakeFailure() {
        FakeConnector connector = new FakeConnector();
        RecordingListener listener = new RecordingListener();
        JdkGatewayTransport transport = new JdkGatewayTransport(connector, 128);
        CompletionStage<GatewayConnection> connecting = transport.connect(GATEWAY_URL, listener);

        connector.opening.completeExceptionally(new IllegalStateException("handshake failed"));

        assertThatThrownBy(() -> connecting.toCompletableFuture().join())
                .hasRootCauseMessage("handshake failed");
        assertThat(listener.events).isEmpty();

        FakeConnector closeConnector = new FakeConnector();
        RecordingListener closeListener = new RecordingListener();
        transport = new JdkGatewayTransport(closeConnector, 128);
        transport.connect(GATEWAY_URL, closeListener);
        closeConnector.opening.complete(closeConnector.socket);
        closeConnector.listener.onClose(closeConnector.socket, 4009, "session timeout");
        closeConnector.listener.onError(closeConnector.socket, new IllegalStateException("late"));

        assertThat(closeListener.events).containsExactly("closed:4009");
    }

    private static final class FakeConnector implements JdkGatewayTransport.WebSocketConnector {
        private final CompletableFuture<WebSocket> opening = new CompletableFuture<>();
        private final FakeWebSocket socket = new FakeWebSocket();
        private WebSocket.Listener listener;

        @Override
        public CompletionStage<WebSocket> connect(URI uri, WebSocket.Listener listener) {
            assertThat(uri).isSameAs(GATEWAY_URL);
            this.listener = listener;
            return opening;
        }
    }

    private static final class RecordingListener implements GatewayTransport.Listener {
        private final List<String> events = new ArrayList<>();

        @Override
        public void onText(String payload) {
            events.add("text:" + payload);
        }

        @Override
        public void onClosed(int statusCode, String reason) {
            events.add("closed:" + statusCode);
        }

        @Override
        public void onFailure(Throwable cause) {
            events.add("failure:" + cause.getClass().getSimpleName());
        }
    }

    private static final class FakeWebSocket implements WebSocket {
        private final List<String> sentTexts = new ArrayList<>();
        private final List<Integer> closeCodes = new ArrayList<>();
        private long requested;
        private boolean aborted;

        @Override
        public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) {
            sentTexts.add(data.toString());
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendBinary(ByteBuffer data, boolean last) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendPing(ByteBuffer message) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendPong(ByteBuffer message) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendClose(int statusCode, String reason) {
            closeCodes.add(statusCode);
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public void request(long n) {
            requested += n;
        }

        @Override
        public String getSubprotocol() {
            return "";
        }

        @Override
        public boolean isOutputClosed() {
            return false;
        }

        @Override
        public boolean isInputClosed() {
            return false;
        }

        @Override
        public void abort() {
            aborted = true;
        }
    }
}
