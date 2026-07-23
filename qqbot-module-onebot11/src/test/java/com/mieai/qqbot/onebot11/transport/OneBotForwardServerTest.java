package com.mieai.qqbot.onebot11.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mieai.qqbot.domain.bot.BotId;
import com.mieai.qqbot.onebot11.protocol.OneBotActionService;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.exceptions.InvalidDataException;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.handshake.ServerHandshake;
import org.junit.jupiter.api.Test;

class OneBotForwardServerTest {
    @Test
    void acceptsUniversalConnectionsAndReturnsActionResponses() throws Exception {
        BotId botId = BotId.of(UUID.randomUUID());
        OneBotActionService actions = mock(OneBotActionService.class);
        when(actions.handle(botId, "{\"action\":\"get_status\"}"))
                .thenReturn(CompletableFuture.completedFuture("{\"status\":\"ok\"}"));
        OneBotForwardServer server = new OneBotForwardServer(
                botId, "127.0.0.1", 0, "secret", actions,
                () -> "{\"post_type\":\"meta_event\"}", ignored -> {}, () -> {});
        server.startListening();
        List<String> received = new CopyOnWriteArrayList<>();
        CountDownLatch messages = new CountDownLatch(2);
        WebSocketClient client = new WebSocketClient(
                URI.create("ws://127.0.0.1:" + server.getPort() + "/"),
                new Draft_6455(), Map.of("Authorization", "Bearer secret"), 5_000) {
            @Override public void onOpen(ServerHandshake handshake) {
                send("{\"action\":\"get_status\"}");
            }
            @Override public void onMessage(String message) {
                received.add(message);
                messages.countDown();
            }
            @Override public void onClose(int code, String reason, boolean remote) {}
            @Override public void onError(Exception exception) {}
        };
        try {
            assertThat(client.connectBlocking(5, TimeUnit.SECONDS)).isTrue();
            assertThat(messages.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(received).containsExactly(
                    "{\"post_type\":\"meta_event\"}", "{\"status\":\"ok\"}");
        } finally {
            client.closeBlocking();
            server.close();
        }
    }

    @Test
    void rejectsInvalidBearerTokensDuringHandshake() {
        OneBotForwardServer server = new OneBotForwardServer(
                BotId.of(UUID.randomUUID()), "127.0.0.1", 0, "expected",
                mock(OneBotActionService.class), () -> "{}", ignored -> {}, () -> {});
        ClientHandshake handshake = mock(ClientHandshake.class);
        when(handshake.getResourceDescriptor()).thenReturn("/api");
        when(handshake.getFieldValue("Authorization")).thenReturn("Bearer wrong");

        assertThatThrownBy(() -> server.onWebsocketHandshakeReceivedAsServer(
                mock(org.java_websocket.WebSocket.class), new Draft_6455(), handshake))
                .isInstanceOf(InvalidDataException.class);
    }
}
