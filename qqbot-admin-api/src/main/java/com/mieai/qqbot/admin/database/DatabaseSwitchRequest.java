package com.mieai.qqbot.admin.database;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record DatabaseSwitchRequest(
        @Positive long expectedRevision,
        @Valid @NotNull DatabaseCandidateRequest candidate) {
}
