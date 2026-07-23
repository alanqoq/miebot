package com.mieai.qqbot.admin.onboarding;

import com.mieai.qqbot.admin.database.DatabaseType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record DatabaseConfiguredRequest(
        @Positive long expectedRevision,
        @NotNull DatabaseType databaseType) {
}
