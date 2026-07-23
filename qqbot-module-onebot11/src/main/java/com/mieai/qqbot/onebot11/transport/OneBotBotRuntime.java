package com.mieai.qqbot.onebot11.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mieai.qqbot.domain.bot.BotId;
import com.mieai.qqbot.onebot11.config.OneBot11Config;
import com.mieai.qqbot.onebot11.config.ResolvedOneBot11Config;
import com.mieai.qqbot.onebot11.protocol.OneBotActionService;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

final class OneBotBotRuntime implements AutoCloseable {
    private final ObjectMapper objectMapper;
    private final ResolvedOneBot11Config resolved;
    private final long selfId;
    private final OneBotMetaEventFactory metaEvents;
    private final OneBotActionService actions;
    private final ScheduledExecutorService heartbeat;
    private volatile OneBotForwardServer forward;
    private volatile OneBotReverseClient reverse;
    private volatile String lastError;
    private volatile boolean closed;

    OneBotBotRuntime(
            ObjectMapper objectMapper,
            ResolvedOneBot11Config resolved,
            long selfId,
            OneBotMetaEventFactory metaEvents,
            OneBotActionService actions) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.resolved = Objects.requireNonNull(resolved, "resolved must not be null");
        this.selfId = selfId;
        this.metaEvents = Objects.requireNonNull(metaEvents, "metaEvents must not be null");
        this.actions = Objects.requireNonNull(actions, "actions must not be null");
        heartbeat = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable,
                    "onebot11-heartbeat-" + botId().toString().substring(0, 8));
            thread.setDaemon(true);
            return thread;
        });
    }

    void start() {
        OneBot11Config config = resolved.config();
        try {
            if (config.forwardEnabled()) {
                forward = new OneBotForwardServer(
                        botId(),
                        config.forwardBindAddress(),
                        config.forwardPort().orElseThrow(),
                        resolved.accessToken(),
                        actions,
                        () -> encode(metaEvents.lifecycle(selfId)),
                        this::recordError,
                        () -> {});
                forward.startListening();
            }
            if (config.reverseEnabled()) {
                reverse = new OneBotReverseClient(
                        botId(),
                        config.reverseUrl().orElseThrow(),
                        selfId,
                        resolved.accessToken(),
                        config.reconnectIntervalMs(),
                        actions,
                        () -> encode(metaEvents.lifecycle(selfId)),
                        this::recordError,
                        () -> {});
                reverse.startConnecting();
            }
            if (config.heartbeatEnabled()) {
                heartbeat.scheduleAtFixedRate(
                        () -> publish(metaEvents.heartbeat(
                                botId(), selfId, config.heartbeatIntervalMs())),
                        config.heartbeatIntervalMs(),
                        config.heartbeatIntervalMs(),
                        TimeUnit.MILLISECONDS);
            }
        } catch (RuntimeException exception) {
            close();
            throw exception;
        }
    }

    BotId botId() {
        return resolved.config().botId();
    }

    long revision() {
        return resolved.config().revision();
    }

    void publish(ObjectNode event) {
        publish(encode(event));
    }

    OneBotTransportStatus status() {
        OneBotForwardServer currentForward = forward;
        OneBotReverseClient currentReverse = reverse;
        boolean forwardListening = currentForward != null && !closed;
        int forwardConnections = currentForward == null ? 0 : currentForward.connectionCount();
        boolean reverseConnected = currentReverse != null && currentReverse.connected();
        String state;
        if (closed) state = "STOPPED";
        else if (reverseConnected || forwardListening) state = "RUNNING";
        else state = "CONNECTING";
        return new OneBotTransportStatus(
                state, forwardListening, forwardConnections, reverseConnected, lastError);
    }

    private void publish(String event) {
        if (closed) return;
        OneBotForwardServer currentForward = forward;
        if (currentForward != null) currentForward.publish(event);
        OneBotReverseClient currentReverse = reverse;
        if (currentReverse != null) currentReverse.publish(event);
    }

    private String encode(ObjectNode event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to encode OneBot event", exception);
        }
    }

    private void recordError(String error) {
        lastError = error;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        heartbeat.shutdownNow();
        OneBotReverseClient currentReverse = reverse;
        reverse = null;
        if (currentReverse != null) currentReverse.close();
        OneBotForwardServer currentForward = forward;
        forward = null;
        if (currentForward != null) currentForward.close();
    }
}
