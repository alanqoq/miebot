package com.mieai.qqbot.runtime.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mieai.qqbot.domain.bot.BotId;
import com.mieai.qqbot.domain.bot.BotRevision;
import java.util.List;
import org.junit.jupiter.api.Test;

class BotConfigurationChangeListenerTest {
    private static final BotId BOT_ID =
            BotId.parse("550e8400-e29b-41d4-a716-446655440000");

    @Test
    void compositeForwardsDeletionPreparationCommitAndAbortHooks() {
        RecordingListener first = new RecordingListener();
        RecordingListener second = new RecordingListener();
        BotConfigurationChangeListener composite =
                BotConfigurationChangeListener.composite(List.of(first, second));
        BotConfigurationChange deleted = new BotConfigurationChange(
                BOT_ID, BotRevision.initial(), false, BotConfigurationChangeKind.DELETED);

        composite.beforeDelete(BOT_ID);
        composite.onDeleteAborted(BOT_ID);
        composite.afterDelete(BOT_ID);
        composite.onCommitted(deleted);

        assertThat(first.beforeDeleteCalls).isEqualTo(1);
        assertThat(second.beforeDeleteCalls).isEqualTo(1);
        assertThat(first.abortCalls).isEqualTo(1);
        assertThat(second.abortCalls).isEqualTo(1);
        assertThat(first.afterDeleteCalls).isEqualTo(1);
        assertThat(second.afterDeleteCalls).isEqualTo(1);
        assertThat(first.committed).containsExactly(deleted);
        assertThat(second.committed).containsExactly(deleted);
    }

    @Test
    void compositeStillInvokesLaterParticipantsWhenOnePreparationFails() {
        RecordingListener first = new RecordingListener();
        RecordingListener second = new RecordingListener();
        first.beforeDeleteFailure = new IllegalStateException("first unavailable");
        BotConfigurationChangeListener composite =
                BotConfigurationChangeListener.composite(List.of(first, second));

        assertThatThrownBy(() -> composite.beforeDelete(BOT_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("first unavailable");

        assertThat(second.beforeDeleteCalls).isEqualTo(1);
    }

    private static final class RecordingListener implements BotConfigurationChangeListener {
        private int beforeDeleteCalls;
        private int abortCalls;
        private int afterDeleteCalls;
        private final java.util.ArrayList<BotConfigurationChange> committed = new java.util.ArrayList<>();
        private RuntimeException beforeDeleteFailure;

        @Override
        public void beforeDelete(BotId botId) {
            beforeDeleteCalls++;
            if (beforeDeleteFailure != null) throw beforeDeleteFailure;
        }

        @Override
        public void onDeleteAborted(BotId botId) {
            abortCalls++;
        }

        @Override
        public void afterDelete(BotId botId) {
            afterDeleteCalls++;
        }

        @Override
        public void onCommitted(BotConfigurationChange change) {
            committed.add(change);
        }
    }
}
