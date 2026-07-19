package com.mieai.qqbot.persistence.inbox;

import java.util.Objects;

/** Result of an idempotent Inbox insert. */
public record InboxInsertResult(boolean inserted, InboxEvent event) {
    public InboxInsertResult {
        Objects.requireNonNull(event, "event must not be null");
    }
}
