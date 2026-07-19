package com.mieai.qqbot.admin.onboarding;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.mieai.qqbot.admin.database.DatabaseType;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record OnboardingStatusResponse(
        OnboardingStage stage,
        DatabaseType databaseType,
        long botCount,
        Instant completedAt) {
}
