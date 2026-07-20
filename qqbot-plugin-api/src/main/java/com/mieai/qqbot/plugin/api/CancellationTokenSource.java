package com.mieai.qqbot.plugin.api;

import java.util.concurrent.atomic.AtomicBoolean;

/** Host/plugin-owned source for a cooperative {@link CancellationToken}. */
public final class CancellationTokenSource {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final CancellationToken token = new CancellationToken(cancelled);

    public CancellationToken token() {
        return token;
    }

    public boolean cancel() {
        return cancelled.compareAndSet(false, true);
    }
}
