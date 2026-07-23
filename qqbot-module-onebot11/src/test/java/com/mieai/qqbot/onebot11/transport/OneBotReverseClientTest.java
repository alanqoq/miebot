package com.mieai.qqbot.onebot11.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mieai.qqbot.domain.bot.BotId;
import com.mieai.qqbot.onebot11.protocol.OneBotActionService;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.junit.jupiter.api.Test;

class OneBotReverseClientTest {
    @Test
    void connectsAsUniversalClientWithRequiredHeaders() throws Exception {
        CountDownLatch serverStarted = new CountDownLatch(1);
        CountDownLatch messagesReceived = new CountDownLatch(2);
        List<String> received = new CopyOnWriteArrayList<>();
        String[] headers = new String[3];
        WebSocketServer server = new WebSocketServer(new InetSocketAddress("127.0.0.1", 0)) {
            @Override public void onOpen(WebSocket connection, ClientHandshake handshake) {
                headers[0] = handshake.getFieldValue("Authorization");
                headers[1] = handshake.getFieldValue("X-Self-ID");
                headers[2] = handshake.getFieldValue("X-Client-Role");
                connection.send("{\"action\":\"get_version_info\"}");
            }
            @Override public void onClose(WebSocket connection, int code, String reason, boolean remote) {}
            @Override public void onMessage(WebSocket connection, String message) {
                received.add(message);
                messagesReceived.countDown();
            }
            @Override public void onError(WebSocket connection, Exception exception) {}
            @Override public void onStart() { serverStarted.countDown(); }
        };
        server.setDaemon(true);
        server.start();
        assertThat(serverStarted.await(5, TimeUnit.SECONDS)).isTrue();

        BotId botId = BotId.of(UUID.randomUUID());
        OneBotActionService actions = mock(OneBotActionService.class);
        when(actions.handle(botId, "{\"action\":\"get_version_info\"}"))
                .thenReturn(CompletableFuture.completedFuture("{\"status\":\"ok\"}"));
        OneBotReverseClient client = new OneBotReverseClient(
                botId,
                URI.create("ws://127.0.0.1:" + server.getPort() + "/onebot"),
                42L,
                "reverse-secret",
                500,
                actions,
                () -> "{\"post_type\":\"meta_event\"}",
                ignored -> {},
                () -> {});
        try {
            client.startConnecting();
            assertThat(messagesReceived.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(headers).containsExactly(
                    "Bearer reverse-secret", "42", "Universal");
            assertThat(received).contains(
                    "{\"post_type\":\"meta_event\"}", "{\"status\":\"ok\"}");
        } finally {
            client.close();
            server.stop(3_000);
        }
    }
}
