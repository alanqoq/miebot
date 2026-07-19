package com.mieai.qqbot.persistence.inbox;

import java.util.UUID;

/** Indicates that an Inbox lease was lost before a fenced state transition. */
public final class InboxTransitionException extends RuntimeException {
    private final UUID eventId;
    private final long fencingToken;
    private final InboxStatus targetStatus;

    public InboxTransitionException(UUID eventId, long fencingToken, InboxStatus targetStatus) {
        super("Inbox event " + eventId + " could not transition to " + targetStatus
                + " with fencing token " + fencingToken);
        this.eventId = eventId;
        this.fencingToken = fencingToken;
        this.targetStatus = targetStatus;
    }

    public UUID eventId() { return eventId; }
    public long fencingToken() { return fencingToken; }
    public InboxStatus targetStatus() { return targetStatus; }
}
