package com.mieai.qqbot.runtime.configuration;

import com.mieai.qqbot.domain.bot.BotId;
import java.util.List;
import java.util.Objects;

/** In-process participant in bot configuration changes. */
@FunctionalInterface
public interface BotConfigurationChangeListener {
    void onCommitted(BotConfigurationChange change);

    /**
     * Prepares local resources for deletion before the durable bot row is removed.
     *
     * <p>Throwing aborts the deletion. Implementations must remain prepared until either
     * {@link #afterDelete(BotId)} or {@link #onDeleteAborted(BotId)} is called.
     */
    default void beforeDelete(BotId botId) {}

    /**
     * Releases or resolves preparation state when the durable delete did not report success.
     *
     * <p>Implementations that manage non-database resources should tolerate an ambiguous database
     * outcome and inspect durable state before restoring those resources.
     */
    default void onDeleteAborted(BotId botId) {}

    /**
     * Finalizes prepared resources after the durable bot row was removed.
     *
     * <p>Unlike {@link #onCommitted(BotConfigurationChange)}, failures from this hook are reported
     * to the caller. Implementations must leave failed cleanup in a state that can be retried.
     */
    default void afterDelete(BotId botId) {}

    static BotConfigurationChangeListener none() {
        return ignored -> {};
    }

    static BotConfigurationChangeListener composite(
            List<? extends BotConfigurationChangeListener> listeners) {
        List<? extends BotConfigurationChangeListener> delegates =
                List.copyOf(Objects.requireNonNull(listeners, "listeners must not be null"));
        return new BotConfigurationChangeListener() {
            @Override
            public void beforeDelete(BotId botId) {
                invokeAll(delegate -> delegate.beforeDelete(botId));
            }

            @Override
            public void onDeleteAborted(BotId botId) {
                invokeAll(delegate -> delegate.onDeleteAborted(botId));
            }

            @Override
            public void afterDelete(BotId botId) {
                invokeAll(delegate -> delegate.afterDelete(botId));
            }

            @Override
            public void onCommitted(BotConfigurationChange change) {
                invokeAll(delegate -> delegate.onCommitted(change));
            }

            private void invokeAll(java.util.function.Consumer<BotConfigurationChangeListener> action) {
                RuntimeException firstFailure = null;
                for (BotConfigurationChangeListener delegate : delegates) {
                    try {
                        action.accept(delegate);
                    } catch (RuntimeException failure) {
                        if (firstFailure == null) {
                            firstFailure = failure;
                        } else {
                            firstFailure.addSuppressed(failure);
                        }
                    }
                }
                if (firstFailure != null) {
                    throw firstFailure;
                }
            }
        };
    }
}
