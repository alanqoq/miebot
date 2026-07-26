package com.mieai.qqbot.app.database

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.SQLException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import javax.sql.DataSource
import org.springframework.jdbc.datasource.AbstractDataSource

/** Stable DataSource facade that drains borrowed connections before changing delegates. */
class SwitchableDataSource(initialDelegate: DataSource) : AbstractDataSource(), AutoCloseable {
    private val lifecycleLock = ReentrantLock(true)
    private val lifecycleChanged = lifecycleLock.newCondition()

    @Volatile
    private var delegate: DataSource = initialDelegate
    private var borrowedConnections = 0
    private var transitionInProgress = false
    private var closed = false

    override fun getConnection(): Connection = borrow(DataSource::getConnection)

    override fun getConnection(username: String, password: String): Connection =
        borrow { it.getConnection(username, password) }

    fun swap(nextDelegate: DataSource): DataSource = beginSwap(nextDelegate).use { swap ->
        val previous = swap.previousDelegate
        swap.commit()
        previous
    }

    /** Keeps new borrowers outside the facade until the returned transaction commits. */
    fun beginSwap(nextDelegate: DataSource): Swap {
        lifecycleLock.lock()
        var established = false
        try {
            while (transitionInProgress) lifecycleChanged.awaitUninterruptibly()
            check(!closed) { "datasource has been closed" }
            transitionInProgress = true
            while (borrowedConnections > 0) lifecycleChanged.awaitUninterruptibly()

            val previous = delegate
            delegate = nextDelegate
            established = true
            return Swap(previous)
        } finally {
            if (!established) {
                transitionInProgress = false
                lifecycleChanged.signalAll()
                lifecycleLock.unlock()
            }
        }
    }

    val currentDelegate: DataSource
        get() = delegate

    override fun close() {
        lifecycleLock.lock()
        try {
            while (transitionInProgress) lifecycleChanged.awaitUninterruptibly()
            if (closed) return
            transitionInProgress = true
            while (borrowedConnections > 0) lifecycleChanged.awaitUninterruptibly()
            closed = true
            closeDataSource(delegate)
        } finally {
            transitionInProgress = false
            lifecycleChanged.signalAll()
            lifecycleLock.unlock()
        }
    }

    @Throws(SQLException::class)
    private fun borrow(supplier: (DataSource) -> Connection): Connection {
        val selected: DataSource
        lifecycleLock.lock()
        try {
            while (transitionInProgress && !closed) lifecycleChanged.awaitUninterruptibly()
            if (closed) throw SQLException("datasource has been closed")
            selected = delegate
            borrowedConnections++
        } finally {
            lifecycleLock.unlock()
        }

        try {
            return guarded(requireNotNull(supplier(selected)) { "delegate returned a null connection" })
        } catch (exception: Throwable) {
            releaseBorrow()
            throw exception
        }
    }

    private fun guarded(connection: Connection): Connection {
        val released = AtomicBoolean()
        return Proxy.newProxyInstance(
            SwitchableDataSource::class.java.classLoader,
            arrayOf(Connection::class.java),
        ) { proxy, method, arguments ->
            invokeConnection(proxy, connection, released, method, arguments)
        } as Connection
    }

    private fun invokeConnection(
        proxy: Any,
        connection: Connection,
        released: AtomicBoolean,
        method: Method,
        arguments: Array<out Any?>?,
    ): Any? {
        val name = method.name
        if (name == "close" && method.parameterCount == 0) {
            try {
                connection.close()
            } finally {
                release(released)
            }
            return null
        }
        if (name == "abort" && method.parameterCount == 1) {
            try {
                return invoke(connection, method, arguments)
            } finally {
                release(released)
            }
        }
        if (name == "isClosed" && released.get()) return true
        if (name == "unwrap" && method.parameterCount == 1) {
            val requested = arguments?.get(0) as Class<*>
            if (requested.isInstance(proxy)) return proxy
        }
        if (name == "isWrapperFor" && method.parameterCount == 1) {
            val requested = arguments?.get(0) as Class<*>
            if (requested.isInstance(proxy)) return true
        }
        return invoke(connection, method, arguments)
    }

    private fun release(released: AtomicBoolean) {
        if (released.compareAndSet(false, true)) releaseBorrow()
    }

    private fun releaseBorrow() {
        lifecycleLock.lock()
        try {
            check(borrowedConnections > 0) { "datasource connection lease underflow" }
            borrowedConnections--
            if (borrowedConnections == 0) lifecycleChanged.signalAll()
        } finally {
            lifecycleLock.unlock()
        }
    }

    inner class Swap internal constructor(val previousDelegate: DataSource) : AutoCloseable {
        private val owner = Thread.currentThread()
        private var completed = false

        fun commit() = complete(false)

        override fun close() {
            if (!completed) complete(true)
        }

        private fun complete(restorePrevious: Boolean) {
            check(Thread.currentThread() === owner) { "datasource swap must be completed by its owner thread" }
            if (completed) return
            if (restorePrevious) delegate = previousDelegate
            completed = true
            transitionInProgress = false
            lifecycleChanged.signalAll()
            lifecycleLock.unlock()
        }
    }

    companion object {
        fun closeDataSource(dataSource: DataSource) {
            if (dataSource is AutoCloseable) {
                try {
                    dataSource.close()
                } catch (_: Exception) {
                    // A retired pool is already unreachable for new work.
                }
            }
        }

        @Throws(Throwable::class)
        private fun invoke(
            connection: Connection,
            method: Method,
            arguments: Array<out Any?>?,
        ): Any? = try {
            if (arguments == null) method.invoke(connection) else method.invoke(connection, *arguments)
        } catch (exception: InvocationTargetException) {
            throw exception.cause ?: exception
        }
    }
}
