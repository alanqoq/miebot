package com.mieai.qqbot.onebot11.transport;

import com.mieai.qqbot.domain.bot.BotId;
import com.mieai.qqbot.onebot11.protocol.OneBotActionService;
import java.net.URI;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.framing.CloseFrame;
import org.java_websocket.handshake.ServerHandshake;

final class OneBotReverseClient implements AutoCloseable {
    private static final int MAX_ACTION_CHARACTERS = 1_048_576;

    private final BotId botId;
    private final URI url;
    private final long selfId;
    private final String accessToken;
    private final int reconnectIntervalMs;
    private final OneBotActionService actions;
    private final Supplier<String> lifecycleEvent;
    private final Consumer<String> errorListener;
    private final Runnable stateListener;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            runnable -> {
                Thread thread = new Thread(runnable, "onebot11-reverse-" + botIdFragment());
                thread.setDaemon(true);
                return thread;
            });
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean();
    private volatile Client client;
    private volatile boolean stopped;

    OneBotReverseClient(
            BotId botId,
            URI url,
            long selfId,
            String accessToken,
            int reconnectIntervalMs,
            OneBotActionService actions,
            Supplier<String> lifecycleEvent,
            Consumer<String> errorListener,
            Runnable stateListener) {
        this.botId = Objects.requireNonNull(botId, "botId must not be null");
        this.url = Objects.requireNonNull(url, "url must not be null");
        this.selfId = selfId;
        this.accessToken = Objects.requireNonNull(accessToken, "accessToken must not be null");
        this.reconnectIntervalMs = reconnectIntervalMs;
        this.actions = Objects.requireNonNull(actions, "actions must not be null");
        this.lifecycleEvent = Objects.requireNonNull(lifecycleEvent, "lifecycleEvent must not be null");
        this.errorListener = Objects.requireNonNull(errorListener, "errorListener must not be null");
        this.stateListener = Objects.requireNonNull(stateListener, "stateListener must not be null");
    }

    void startConnecting() {
        scheduler.execute(this::connectNew);
    }

    void publish(String event) {
        Client current = client;
        if (current == null || !current.isOpen()) {
            return;
        }
        if (current.hasBufferedData()) {
            current.close(CloseFrame.POLICY_VALIDATION, "Event consumer is too slow");
        } else {
            current.send(event);
        }
    }

    boolean connected() {
        Client current = client;
        return current != null && current.isOpen();
    }

    private synchronized void connectNew() {
        reconnectScheduled.set(false);
        if (stopped) {
            return;
        }
        Client previous = client;
        if (previous != null && !previous.isClosed()) {
            return;
        }
        Client replacement = new Client();
        replacement.setConnectionLostTimeout(30);
        client = replacement;
        replacement.connect();
        stateListener.run();
    }

    private void scheduleReconnect() {
        if (stopped || !reconnectScheduled.compareAndSet(false, true)) {
            return;
        }
        scheduler.schedule(this::connectNew, reconnectIntervalMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void close() {
        if (stopped) {
            return;
        }
        stopped = true;
        Client current = client;
        if (current != null) {
            current.close(CloseFrame.NORMAL, "OneBot transport stopped");
        }
        scheduler.shutdownNow();
        stateListener.run();
    }

    private String botIdFragment() {
        String value = botId == null ? "starting" : botId.toString();
        return value.substring(0, Math.min(8, value.length()));
    }

    private final class Client extends WebSocketClient {
        private Client() {
            super(url, new Draft_6455(), Map.of(
                    "Authorization", "Bearer " + accessToken,
                    "X-Self-ID", Long.toString(selfId),
                    "X-Client-Role", "Universal"), 10_000);
        }

        @Override
        public void onOpen(ServerHandshake handshake) {
            reconnectScheduled.set(false);
            send(lifecycleEvent.get());
            stateListener.run();
        }

        @Override
        public void onMessage(String message) {
            if (message.length() > MAX_ACTION_CHARACTERS) {
                close(CloseFrame.TOOBIG, "Action frame is too large");
                return;
            }
            actions.handle(botId, message).whenComplete((response, failure) -> {
                if (!isOpen()) return;
                if (failure != null) {
                    close(CloseFrame.UNEXPECTED_CONDITION, "Action processing failed");
                } else {
                    send(response);
                }
            });
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {
            stateListener.run();
            scheduleReconnect();
        }

        @Override
        public void onError(Exception exception) {
            errorListener.accept("Reverse WebSocket connection failed");
            stateListener.run();
        }
    }
}
