package com.mieai.qqbot.admin.onboarding

import com.mieai.qqbot.admin.database.DatabaseType
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive

data class DatabaseConfiguredRequest(
    @field:Positive val expectedRevision: Long,
    @field:NotNull val databaseType: DatabaseType?,
)
