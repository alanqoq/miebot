package com.mieai.qqbot.admin.security

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class LoginRequest(
    @field:NotBlank @field:Size(max = 64) val username: String?,
    @field:NotBlank @field:Size(max = 72) val password: String?,
)
