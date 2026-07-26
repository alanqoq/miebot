package com.mieai.qqbot.plugin.api

import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/** Cooperative cancellation signal for one plugin invocation. */
class CancellationToken internal constructor(
    private val cancelled: AtomicBoolean,
) {
    /** Returns the invocation token on a host callback thread, or a live no-op token otherwise. */
    val isCancellationRequested: Boolean
        get() = cancelled.get()

    fun throwIfCancellationRequested() {
        if (isCancellationRequested) {
            throw CancellationException("Plugin invocation was cancelled")
        }
    }

    /** Host hook used to bind a token to a synchronous callback. */
    fun interface Scope : AutoCloseable {
        override fun close()
    }

    companion object {
        private val currentToken = ThreadLocal<CancellationToken>()
        private val never = CancellationToken(AtomicBoolean())

        fun current(): CancellationToken = currentToken.get() ?: never

        fun activate(token: CancellationToken): Scope {
            val previous = currentToken.get()
            currentToken.set(token)
            return Scope {
                if (previous == null) {
                    currentToken.remove()
                } else {
                    currentToken.set(previous)
                }
            }
        }
    }
}
