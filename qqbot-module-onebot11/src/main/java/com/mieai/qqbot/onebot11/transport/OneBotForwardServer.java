package com.mieai.qqbot.onebot11.transport;

import com.mieai.qqbot.domain.bot.BotId;
import com.mieai.qqbot.onebot11.protocol.OneBotActionService;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.java_websocket.WebSocket;
import org.java_websocket.drafts.Draft;
import org.java_websocket.exceptions.InvalidDataException;
import org.java_websocket.framing.CloseFrame;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.handshake.ServerHandshakeBuilder;
import org.java_websocket.server.WebSocketServer;

final class OneBotForwardServer extends WebSocketServer implements AutoCloseable {
    private static final int MAX_ACTION_CHARACTERS = 1_048_576;

    private final BotId botId;
    private final String accessToken;
    private final OneBotActionService actions;
    private final Supplier<String> lifecycleEvent;
    private final Consumer<String> errorListener;
    private final Runnable stateListener;
    private final CountDownLatch started = new CountDownLatch(1);
    private final AtomicReference<RuntimeException> startFailure = new AtomicReference<>();

    OneBotForwardServer(
            BotId botId,
            String bindAddress,
            int port,
            String accessToken,
            OneBotActionService actions,
            Supplier<String> lifecycleEvent,
            Consumer<String> errorListener,
            Runnable stateListener) {
        super(new InetSocketAddress(bindAddress, port));
        this.botId = Objects.requireNonNull(botId, "botId must not be null");
        this.accessToken = Objects.requireNonNull(accessToken, "accessToken must not be null");
        this.actions = Objects.requireNonNull(actions, "actions must not be null");
        this.lifecycleEvent = Objects.requireNonNull(lifecycleEvent, "lifecycleEvent must not be null");
        this.errorListener = Objects.requireNonNull(errorListener, "errorListener must not be null");
        this.stateListener = Objects.requireNonNull(stateListener, "stateListener must not be null");
        setReuseAddr(true);
        setConnectionLostTimeout(30);
        setMaxPendingConnections(64);
        setDaemon(true);
    }

    void startListening() {
        start();
        try {
            if (!started.await(10, TimeUnit.SECONDS)) {
                close();
                throw new IllegalStateException("Forward WebSocket listener timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Forward WebSocket startup was interrupted", exception);
        }
        RuntimeException failure = startFailure.get();
        if (failure != null) {
            throw failure;
        }
    }

    @Override
    public ServerHandshakeBuilder onWebsocketHandshakeReceivedAsServer(
            WebSocket connection, Draft draft, ClientHandshake request)
            throws InvalidDataException {
        OneBotConnectionRole role = role(request.getResourceDescriptor());
        if (role == null || !authorized(request)) {
            throw new InvalidDataException(CloseFrame.POLICY_VALIDATION, "Unauthorized");
        }
        connection.setAttachment(role);
        return super.onWebsocketHandshakeReceivedAsServer(connection, draft, request);
    }

    @Override
    public void onOpen(WebSocket connection, ClientHandshake handshake) {
        OneBotConnectionRole role = connection.getAttachment();
        if (role != null && role.acceptsEvents()) {
            connection.send(lifecycleEvent.get());
        }
        stateListener.run();
    }

    @Override
    public void onClose(WebSocket connection, int code, String reason, boolean remote) {
        stateListener.run();
    }

    @Override
    public void onMessage(WebSocket connection, String message) {
        OneBotConnectionRole role = connection.getAttachment();
        if (role == null || !role.acceptsActions()) {
            connection.close(CloseFrame.POLICY_VALIDATION, "This endpoint only accepts events");
            return;
        }
        if (message.length() > MAX_ACTION_CHARACTERS) {
            connection.close(CloseFrame.TOOBIG, "Action frame is too large");
            return;
        }
        actions.handle(botId, message).whenComplete((response, failure) -> {
            if (!connection.isOpen()) {
                return;
            }
            if (failure != null) {
                connection.close(CloseFrame.UNEXPECTED_CONDITION, "Action processing failed");
            } else {
                connection.send(response);
            }
        });
    }

    @Override
    public void onError(WebSocket connection, Exception exception) {
        if (started.getCount() > 0L) {
            startFailure.compareAndSet(null,
                    new IllegalStateException("Unable to bind forward WebSocket listener", exception));
            started.countDown();
        } else {
            errorListener.accept("Forward WebSocket transport failed");
        }
        stateListener.run();
    }

    @Override
    public void onStart() {
        started.countDown();
        stateListener.run();
    }

    void publish(String event) {
        for (WebSocket connection : getConnections()) {
            OneBotConnectionRole role = connection.getAttachment();
            if (connection.isOpen() && role != null && role.acceptsEvents()) {
                if (connection.hasBufferedData()) {
                    connection.close(CloseFrame.POLICY_VALIDATION, "Event consumer is too slow");
                } else {
                    connection.send(event);
                }
            }
        }
    }

    int connectionCount() {
        return getConnections().size();
    }

    @Override
    public void close() {
        try {
            stop(3_000, "OneBot transport stopped");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean authorized(ClientHandshake request) {
        String authorization = request.getFieldValue("Authorization");
        String candidate = null;
        if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            candidate = authorization.substring(7);
        }
        if (candidate == null || candidate.isEmpty()) {
            candidate = queryToken(request.getResourceDescriptor());
        }
        return candidate != null && MessageDigest.isEqual(
                accessToken.getBytes(StandardCharsets.UTF_8),
                candidate.getBytes(StandardCharsets.UTF_8));
    }

    private static OneBotConnectionRole role(String resource) {
        String path = resource;
        int query = path.indexOf('?');
        if (query >= 0) path = path.substring(0, query);
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return switch (path) {
            case "/api" -> OneBotConnectionRole.API;
            case "/event" -> OneBotConnectionRole.EVENT;
            case "/" -> OneBotConnectionRole.UNIVERSAL;
            default -> null;
        };
    }

    private static String queryToken(String resource) {
        int query = resource.indexOf('?');
        if (query < 0 || query == resource.length() - 1) return null;
        for (String item : resource.substring(query + 1).split("&")) {
            int equals = item.indexOf('=');
            String name = equals < 0 ? item : item.substring(0, equals);
            if (name.equals("access_token")) {
                String value = equals < 0 ? "" : item.substring(equals + 1);
                try {
                    return URLDecoder.decode(value, StandardCharsets.UTF_8);
                } catch (IllegalArgumentException exception) {
                    return null;
                }
            }
        }
        return null;
    }
}
