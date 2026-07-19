package com.mieai.qqbot.persistence.bot;

import com.mieai.qqbot.domain.bot.BotId;
import com.mieai.qqbot.domain.bot.BotRevision;
import java.util.List;
import java.util.Optional;

/** Persistence contract for bot configuration and encrypted credentials. */
public interface BotRepository {
    Optional<StoredBot> findById(BotId id);

    List<StoredBot> findAll();

    List<StoredBot> findEnabled();

    void insert(StoredBot bot);

    /**
     * Updates a bot if its stored revision matches {@code expectedRevision}.
     *
     * @return the revision stored by this update
     * @throws OptimisticLockException if no row has the expected revision
     */
    BotRevision update(StoredBot bot, BotRevision expectedRevision);

    /** Deletes a bot and its durable runtime data as one database operation. */
    default boolean delete(BotId id) {
        throw new UnsupportedOperationException("bot deletion is not supported by this repository");
    }
}
