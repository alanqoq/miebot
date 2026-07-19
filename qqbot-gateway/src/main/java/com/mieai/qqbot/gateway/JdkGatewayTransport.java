package com.mieai.qqbot.gateway;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Production QQ Gateway transport backed by the Java 21 HTTP client. */
public final class JdkGatewayTransport implements GatewayTransport {
    public static final int DEFAULT_MAX_TEXT_CHARACTERS = 2 * 1024 * 1024;
    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private final WebSocketConnector connector;
    private final int maxTextCharacters;

    public JdkGatewayTransport() {
        this(DEFAULT_CONNECT_TIMEOUT, DEFAULT_MAX_TEXT_CHARACTERS);
    }

    public JdkGatewayTransport(Duration connectTimeout, int maxTextCharacters) {
        Objects.requireNonNull(connectTimeout, "connectTimeout must not be null");
        if (connectTimeout.isZero() || connectTimeout.isNegative()) {
            throw new IllegalArgumentException("connectTimeout must be positive");
        }
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.connector = (uri, listener) -> client.newWebSocketBuilder()
                .connectTimeout(connectTimeout)
                .buildAsync(uri, listener);
        this.maxTextCharacters = requireMaxTextCharacters(maxTextCharacters);
    }

    JdkGatewayTransport(WebSocketConnector connector, int maxTextCharacters) {
        this.connector = Objects.requireNonNull(connector, "connector must not be null");
        this.maxTextCharacters = requireMaxTextCharacters(maxTextCharacters);
    }

    @Override
    public CompletionStage<GatewayConnection> connect(URI gatewayUrl, Listener listener) {
        Objects.requireNonNull(gatewayUrl, "gatewayUrl must not be null");
        Objects.requireNonNull(listener, "listener must not be null");

        CompletableFuture<GatewayConnection> result = new CompletableFuture<>();
        OrderedWebSocketListener socketListener =
                new OrderedWebSocketListener(listener, maxTextCharacters);
        CompletionStage<WebSocket> opening;
        try {
            opening = Objects.requireNonNull(
                    connector.connect(gatewayUrl, socketListener),
                    "connector returned null");
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
        opening.whenComplete((socket, throwable) -> {
            if (throwable != null) {
                socketListener.discard();
                result.completeExceptionally(unwrap(throwable));
                return;
            }
            if (socket == null) {
                socketListener.discard();
                result.completeExceptionally(new NullPointerException("connector returned a null WebSocket"));
                return;
            }
            result.complete(new JdkGatewayConnection(socket));
            socketListener.publish();
        });
        return result.minimalCompletionStage();
    }

    @FunctionalInterface
    interface WebSocketConnector {
        CompletionStage<WebSocket> connect(URI uri, WebSocket.Listener listener);
    }

    private static int requireMaxTextCharacters(int value) {
        if (value < 1) {
            throw new IllegalArgumentException("maxTextCharacters must be positive");
        }
        return value;
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static final class JdkGatewayConnection implements GatewayConnection {
        private final WebSocket socket;
        private final AtomicBoolean closing = new AtomicBoolean();
        private CompletionStage<Void> sendTail = CompletableFuture.completedFuture(null);
        private CompletionStage<Void> closeStage;

        private JdkGatewayConnection(WebSocket socket) {
            this.socket = socket;
        }

        @Override
        public synchronized CompletionStage<Void> sendText(String payload) {
            Objects.requireNonNull(payload, "payload must not be null");
            if (closing.get()) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("Gateway connection is closing"));
            }
            CompletionStage<Void> sending = sendTail
                    .handle((ignored, previousFailure) -> null)
                    .thenCompose(ignored -> socket.sendText(payload, true))
                    .thenApply(ignored -> null);
            sendTail = sending;
            return sending;
        }

        @Override
        public synchronized CompletionStage<Void> close() {
            if (closeStage != null) {
                return closeStage;
            }
            closing.set(true);
            closeStage = sendTail
                    .handle((ignored, previousFailure) -> null)
                    .thenCompose(ignored -> socket.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown"))
                    .handle((ignored, throwable) -> {
                        if (throwable != null) {
                            socket.abort();
                            throw new CompletionException(unwrap(throwable));
                        }
                        return null;
                    });
            return closeStage;
        }
    }

    private static final class OrderedWebSocketListener implements WebSocket.Listener {
        private final Object monitor = new Object();
        private final Listener listener;
        private final int maxTextCharacters;
        private final StringBuilder text = new StringBuilder();
        private final Queue<Consumer<Listener>> events = new ArrayDeque<>();

        private boolean published;
        private boolean discarded;
        private boolean delivering;
        private boolean terminalQueued;

        private OrderedWebSocketListener(Listener listener, int maxTextCharacters) {
            this.listener = listener;
            this.maxTextCharacters = maxTextCharacters;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1L);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            String completed = null;
            RuntimeException oversized = null;
            synchronized (monitor) {
                if (!terminalQueued && !discarded) {
                    if ((long) text.length() + data.length() > maxTextCharacters) {
                        oversized = new IllegalArgumentException(
                                "QQ Gateway text message exceeded the configured limit");
                        text.setLength(0);
                    } else {
                        text.append(data);
                        if (last) {
                            completed = text.toString();
                            text.setLength(0);
                        }
                    }
                }
            }
            if (oversized != null) {
                webSocket.abort();
                RuntimeException failure = oversized;
                enqueueTerminal(target -> target.onFailure(failure));
            } else if (completed != null) {
                String payload = completed;
                enqueue(target -> target.onText(payload));
            }
            webSocket.request(1L);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            webSocket.abort();
            enqueueTerminal(target -> target.onFailure(
                    new IllegalArgumentException("QQ Gateway sent an unsupported binary message")));
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
            webSocket.request(1L);
            return webSocket.sendPong(message);
        }

        @Override
        public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
            webSocket.request(1L);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            enqueueTerminal(target -> target.onClosed(statusCode, reason == null ? "" : reason));
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            enqueueTerminal(target -> target.onFailure(
                    Objects.requireNonNull(error, "WebSocket error must not be null")));
        }

        private void publish() {
            boolean drain;
            synchronized (monitor) {
                if (discarded) {
                    return;
                }
                published = true;
                drain = !delivering && !events.isEmpty();
                if (drain) {
                    delivering = true;
                }
            }
            if (drain) {
                drain();
            }
        }

        private void discard() {
            synchronized (monitor) {
                discarded = true;
                events.clear();
                text.setLength(0);
            }
        }

        private void enqueue(Consumer<Listener> event) {
            enqueue(event, false);
        }

        private void enqueueTerminal(Consumer<Listener> event) {
            enqueue(event, true);
        }

        private void enqueue(Consumer<Listener> event, boolean terminal) {
            boolean drain;
            synchronized (monitor) {
                if (discarded || terminalQueued) {
                    return;
                }
                if (terminal) {
                    terminalQueued = true;
                }
                events.add(event);
                drain = published && !delivering;
                if (drain) {
                    delivering = true;
                }
            }
            if (drain) {
                drain();
            }
        }

        private void drain() {
            while (true) {
                Consumer<Listener> event;
                synchronized (monitor) {
                    event = events.poll();
                    if (event == null) {
                        delivering = false;
                        return;
                    }
                }
                try {
                    event.accept(listener);
                } catch (RuntimeException ignored) {
                    // Transport observation failures must not corrupt WebSocket demand handling.
                }
            }
        }
    }
}
