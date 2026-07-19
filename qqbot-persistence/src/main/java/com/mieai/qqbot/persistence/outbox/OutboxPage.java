package com.mieai.qqbot.persistence.outbox;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** One immutable page of Outbox jobs and an optional cursor for the next page. */
public record OutboxPage(List<OutboxJob> jobs, Optional<String> nextCursor) {
    public OutboxPage {
        jobs = List.copyOf(Objects.requireNonNull(jobs, "jobs must not be null"));
        nextCursor = Objects.requireNonNull(nextCursor, "nextCursor must not be null");
    }
}
