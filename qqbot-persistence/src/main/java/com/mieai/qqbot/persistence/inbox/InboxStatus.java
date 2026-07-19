package com.mieai.qqbot.persistence.inbox;

/** Durable processing state of a platform event. */
public enum InboxStatus {
    RECEIVED,
    PROCESSING,
    DISPATCHED,
    DEAD_LETTER
}
