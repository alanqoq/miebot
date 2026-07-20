package com.mieai.qqbot.plugin.api;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Cooperative cancellation signal for one plugin invocation. */
public final class CancellationToken {
    private static final ThreadLocal<CancellationToken> CURRENT = new ThreadLocal<>();
    private final AtomicBoolean cancelled;

    CancellationToken(AtomicBoolean cancelled) {
        this.cancelled = Objects.requireNonNull(cancelled, "cancelled must not be null");
    }

    /** Returns the invocation token on a host callback thread, or a live no-op token otherwise. */
    public static CancellationToken current() {
        CancellationToken token = CURRENT.get();
        return token == null ? NEVER : token;
    }

    public boolean isCancellationRequested() {
        return cancelled.get();
    }

    public void throwIfCancellationRequested() {
        if (isCancellationRequested()) {
            throw new CancellationException("Plugin invocation was cancelled");
        }
    }

    /** Host hook used to bind a token to a synchronous callback. */
    public static Scope activate(CancellationToken token) {
        CancellationToken previous = CURRENT.get();
        CURRENT.set(Objects.requireNonNull(token, "token must not be null"));
        return () -> {
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
        };
    }

    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    private static final CancellationToken NEVER = new CancellationToken(new AtomicBoolean());
}
