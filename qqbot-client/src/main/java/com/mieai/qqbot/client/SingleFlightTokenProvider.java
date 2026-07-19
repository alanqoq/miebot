package com.mieai.qqbot.client;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;

/** Caches one bot token and coalesces concurrent refreshes into one HTTP request. */
public final class SingleFlightTokenProvider implements TokenProvider {
    private final BotCredentials credentials;
    private final AccessTokenRequester requester;
    private final Duration refreshSkew;
    private final Clock clock;
    private final AtomicReference<State> state = new AtomicReference<>(State.empty());

    public SingleFlightTokenProvider(
            BotCredentials credentials, AccessTokenRequester requester, QqClientOptions options) {
        this(credentials, requester, options.tokenRefreshSkew(), Clock.systemUTC());
    }

    SingleFlightTokenProvider(
            BotCredentials credentials,
            AccessTokenRequester requester,
            Duration refreshSkew,
            Clock clock) {
        this.credentials = Objects.requireNonNull(credentials, "credentials must not be null");
        this.requester = Objects.requireNonNull(requester, "requester must not be null");
        this.refreshSkew = Objects.requireNonNull(refreshSkew, "refreshSkew must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        if (refreshSkew.isNegative()) {
            throw new IllegalArgumentException("refreshSkew must not be negative");
        }
    }

    @Override
    public CompletionStage<AccessToken> getAccessToken() {
        while (true) {
            State current = state.get();
            if (current.token != null && current.token.isUsableAt(clock.instant(), refreshSkew)) {
                return CompletableFuture.completedFuture(current.token);
            }
            if (current.refresh != null) {
                return current.refresh.minimalCompletionStage();
            }

            CompletableFuture<AccessToken> refresh = new CompletableFuture<>();
            State refreshing = new State(current.token, refresh);
            if (!state.compareAndSet(current, refreshing)) {
                continue;
            }

            CompletionStage<AccessToken> request;
            try {
                request = Objects.requireNonNull(
                        requester.requestToken(credentials), "requester returned null");
            } catch (RuntimeException exception) {
                completeFailure(current, refreshing, refresh, exception);
                return refresh.minimalCompletionStage();
            }
            request.whenComplete((token, throwable) -> {
                if (throwable != null) {
                    completeFailure(current, refreshing, refresh, unwrap(throwable));
                    return;
                }
                if (token == null) {
                    completeFailure(
                            current,
                            refreshing,
                            refresh,
                            new NullPointerException("requester returned a null token"));
                    return;
                }
                state.compareAndSet(refreshing, new State(token, null));
                refresh.complete(token);
            });
            return refresh.minimalCompletionStage();
        }
    }

    @Override
    public void invalidate(AccessToken rejectedToken) {
        Objects.requireNonNull(rejectedToken, "rejectedToken must not be null");
        while (true) {
            State current = state.get();
            if (current.refresh != null || current.token != rejectedToken) {
                return;
            }
            if (state.compareAndSet(current, State.empty())) {
                return;
            }
        }
    }

    private void completeFailure(
            State previous,
            State refreshing,
            CompletableFuture<AccessToken> refresh,
            Throwable throwable) {
        state.compareAndSet(refreshing, new State(previous.token, null));
        refresh.completeExceptionally(throwable);
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private record State(AccessToken token, CompletableFuture<AccessToken> refresh) {
        private static State empty() {
            return new State(null, null);
        }
    }
}
