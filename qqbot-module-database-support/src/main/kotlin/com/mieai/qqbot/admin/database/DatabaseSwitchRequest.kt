package com.mieai.qqbot.admin.database

import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive

data class DatabaseSwitchRequest(
    @field:Positive val expectedRevision: Long,
    @field:Valid @field:NotNull val candidate: DatabaseCandidateRequest?,
)
