package com.mieai.qqbot.app.database;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.AbstractDataSource;

/** Stable DataSource facade that drains borrowed connections before changing delegates. */
final class SwitchableDataSource extends AbstractDataSource implements AutoCloseable {
    private final ReentrantLock lifecycleLock = new ReentrantLock(true);
    private final Condition lifecycleChanged = lifecycleLock.newCondition();
    private volatile DataSource delegate;
    private int borrowedConnections;
    private boolean transitionInProgress;
    private boolean closed;

    SwitchableDataSource(DataSource initialDelegate) {
        delegate = Objects.requireNonNull(initialDelegate, "initialDelegate must not be null");
    }

    @Override
    public Connection getConnection() throws SQLException {
        return borrow(DataSource::getConnection);
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return borrow(dataSource -> dataSource.getConnection(username, password));
    }

    DataSource swap(DataSource nextDelegate) {
        try (Swap swap = beginSwap(nextDelegate)) {
            DataSource previous = swap.previousDelegate();
            swap.commit();
            return previous;
        }
    }

    /**
     * Changes the delegate while keeping new borrowers outside the facade until the caller commits.
     * Closing the returned transaction without committing restores the previous delegate.
     */
    Swap beginSwap(DataSource nextDelegate) {
        Objects.requireNonNull(nextDelegate, "nextDelegate must not be null");
        lifecycleLock.lock();
        boolean established = false;
        try {
            while (transitionInProgress) {
                lifecycleChanged.awaitUninterruptibly();
            }
            if (closed) {
                throw new IllegalStateException("datasource has been closed");
            }

            transitionInProgress = true;
            while (borrowedConnections > 0) {
                lifecycleChanged.awaitUninterruptibly();
            }

            DataSource previous = delegate;
            delegate = nextDelegate;
            established = true;
            return new Swap(previous);
        } finally {
            if (!established) {
                transitionInProgress = false;
                lifecycleChanged.signalAll();
                lifecycleLock.unlock();
            }
        }
    }

    DataSource currentDelegate() {
        return delegate;
    }

    @Override
    public void close() {
        lifecycleLock.lock();
        try {
            while (transitionInProgress) {
                lifecycleChanged.awaitUninterruptibly();
            }
            if (closed) {
                return;
            }
            transitionInProgress = true;
            while (borrowedConnections > 0) {
                lifecycleChanged.awaitUninterruptibly();
            }
            closed = true;
            closeDataSource(delegate);
        } finally {
            transitionInProgress = false;
            lifecycleChanged.signalAll();
            lifecycleLock.unlock();
        }
    }

    private Connection borrow(ConnectionSupplier supplier) throws SQLException {
        DataSource selected;
        lifecycleLock.lock();
        try {
            while (transitionInProgress && !closed) {
                lifecycleChanged.awaitUninterruptibly();
            }
            if (closed) {
                throw new SQLException("datasource has been closed");
            }
            selected = delegate;
            borrowedConnections++;
        } finally {
            lifecycleLock.unlock();
        }

        try {
            Connection connection = Objects.requireNonNull(
                    supplier.get(selected), "delegate returned a null connection");
            return guarded(connection);
        } catch (SQLException | RuntimeException | Error exception) {
            releaseBorrow();
            throw exception;
        }
    }

    private Connection guarded(Connection connection) {
        AtomicBoolean released = new AtomicBoolean();
        return (Connection) Proxy.newProxyInstance(
                SwitchableDataSource.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                (proxy, method, arguments) -> invokeConnection(proxy, connection, released, method, arguments));
    }

    private Object invokeConnection(
            Object proxy,
            Connection connection,
            AtomicBoolean released,
            Method method,
            Object[] arguments) throws Throwable {
        String name = method.getName();
        if ("close".equals(name) && method.getParameterCount() == 0) {
            try {
                connection.close();
            } finally {
                release(released);
            }
            return null;
        }
        if ("abort".equals(name) && method.getParameterCount() == 1) {
            try {
                return invoke(connection, method, arguments);
            } finally {
                release(released);
            }
        }
        if ("isClosed".equals(name) && released.get()) {
            return true;
        }
        if ("unwrap".equals(name) && method.getParameterCount() == 1) {
            Class<?> requested = (Class<?>) arguments[0];
            if (requested.isInstance(proxy)) {
                return proxy;
            }
        }
        if ("isWrapperFor".equals(name) && method.getParameterCount() == 1) {
            Class<?> requested = (Class<?>) arguments[0];
            if (requested.isInstance(proxy)) {
                return true;
            }
        }
        return invoke(connection, method, arguments);
    }

    private static Object invoke(Connection connection, Method method, Object[] arguments) throws Throwable {
        try {
            return method.invoke(connection, arguments);
        } catch (InvocationTargetException exception) {
            throw exception.getCause();
        }
    }

    private void release(AtomicBoolean released) {
        if (released.compareAndSet(false, true)) {
            releaseBorrow();
        }
    }

    private void releaseBorrow() {
        lifecycleLock.lock();
        try {
            if (borrowedConnections <= 0) {
                throw new IllegalStateException("datasource connection lease underflow");
            }
            borrowedConnections--;
            if (borrowedConnections == 0) {
                lifecycleChanged.signalAll();
            }
        } finally {
            lifecycleLock.unlock();
        }
    }

    final class Swap implements AutoCloseable {
        private final DataSource previousDelegate;
        private final Thread owner = Thread.currentThread();
        private boolean completed;

        private Swap(DataSource previousDelegate) {
            this.previousDelegate = previousDelegate;
        }

        DataSource previousDelegate() {
            return previousDelegate;
        }

        void commit() {
            complete(false);
        }

        @Override
        public void close() {
            if (!completed) {
                complete(true);
            }
        }

        private void complete(boolean restorePrevious) {
            if (Thread.currentThread() != owner) {
                throw new IllegalStateException("datasource swap must be completed by its owner thread");
            }
            if (completed) {
                return;
            }
            if (restorePrevious) {
                delegate = previousDelegate;
            }
            completed = true;
            transitionInProgress = false;
            lifecycleChanged.signalAll();
            lifecycleLock.unlock();
        }
    }

    static void closeDataSource(DataSource dataSource) {
        if (dataSource instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception ignored) {
                // Closing a retired pool is best effort; it is no longer reachable for new work.
            }
        }
    }

    @FunctionalInterface
    private interface ConnectionSupplier {
        Connection get(DataSource dataSource) throws SQLException;
    }
}
