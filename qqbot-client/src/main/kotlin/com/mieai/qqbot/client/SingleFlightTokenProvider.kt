package com.mieai.qqbot.client

import java.time.Clock
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicReference

/** Caches one bot token and coalesces concurrent refreshes into one HTTP request. */
class SingleFlightTokenProvider(
    private val credentials: BotCredentials,
    private val requester: AccessTokenRequester,
    options: QqClientOptions,
    private val clock: Clock = Clock.systemUTC(),
) : TokenProvider {
    private val state = AtomicReference(State.empty())
    private val refreshSkew = options.tokenRefreshSkew

    init {
        require(!refreshSkew.isNegative) { "refreshSkew must not be negative" }
    }

    override fun getAccessToken(): CompletionStage<AccessToken> {
        while (true) {
            val current = state.get()
            if (current.token != null && current.token.isUsableAt(clock.instant(), refreshSkew)) {
                return CompletableFuture.completedFuture(current.token)
            }
            if (current.refresh != null) {
                return current.refresh.minimalCompletionStage()
            }

            val refresh = CompletableFuture<AccessToken>()
            val refreshing = State(current.token, refresh)
            if (!state.compareAndSet(current, refreshing)) {
                continue
            }

            val request: CompletionStage<AccessToken>
            try {
                request = requester.requestToken(credentials)
            } catch (exception: RuntimeException) {
                completeFailure(current, refreshing, refresh, exception)
                return refresh.minimalCompletionStage()
            }
            request.whenComplete { token, throwable ->
                when {
                    throwable != null -> completeFailure(current, refreshing, refresh, unwrap(throwable))
                    token == null -> completeFailure(
                        current,
                        refreshing,
                        refresh,
                        NullPointerException("requester returned a null token"),
                    )
                    else -> {
                        state.compareAndSet(refreshing, State(token, null))
                        refresh.complete(token)
                    }
                }
            }
            return refresh.minimalCompletionStage()
        }
    }

    override fun invalidate(rejectedToken: AccessToken) {
        while (true) {
            val current = state.get()
            if (current.refresh != null || current.token !== rejectedToken) {
                return
            }
            if (state.compareAndSet(current, State.empty())) {
                return
            }
        }
    }

    private fun completeFailure(
        previous: State,
        refreshing: State,
        refresh: CompletableFuture<AccessToken>,
        throwable: Throwable,
    ) {
        state.compareAndSet(refreshing, State(previous.token, null))
        refresh.completeExceptionally(throwable)
    }

    private fun unwrap(throwable: Throwable): Throwable {
        var current = throwable
        while ((current is CompletionException || current is ExecutionException) && current.cause != null) {
            current = current.cause!!
        }
        return current
    }

    private data class State(
        val token: AccessToken?,
        val refresh: CompletableFuture<AccessToken>?,
    ) {
        companion object {
            fun empty(): State = State(null, null)
        }
    }
}
