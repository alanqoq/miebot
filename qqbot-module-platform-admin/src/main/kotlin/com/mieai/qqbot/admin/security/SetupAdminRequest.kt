package com.mieai.qqbot.admin.security

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

data class SetupAdminRequest(
    @field:NotBlank
    @field:Size(min = 3, max = 64)
    @field:Pattern(regexp = "[A-Za-z0-9._-]+")
    val username: String?,
    @field:NotBlank @field:Size(min = 12, max = 72) val password: String?,
)
