package com.mieai.qqbot.admin.onboarding

import com.fasterxml.jackson.annotation.JsonInclude
import com.mieai.qqbot.admin.database.DatabaseType
import java.time.Instant

@JsonInclude(JsonInclude.Include.ALWAYS)
data class OnboardingStatusResponse(
    val stage: OnboardingStage,
    val databaseType: DatabaseType?,
    val botCount: Long,
    val completedAt: Instant?,
)
