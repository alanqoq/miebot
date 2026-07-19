package com.mieai.qqbot.persistence.inbox;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** One immutable page of Inbox events and an optional cursor for the next page. */
public record InboxPage(List<InboxEvent> events, Optional<String> nextCursor) {
    public InboxPage {
        events = List.copyOf(Objects.requireNonNull(events, "events must not be null"));
        nextCursor = Objects.requireNonNull(nextCursor, "nextCursor must not be null");
    }
}
