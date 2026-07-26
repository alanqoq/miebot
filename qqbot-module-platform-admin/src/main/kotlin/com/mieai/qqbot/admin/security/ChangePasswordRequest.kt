package com.mieai.qqbot.admin.security

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class ChangePasswordRequest(
    @field:NotBlank @field:Size(max = 4096) val currentPassword: String?,
    @field:NotBlank @field:Size(min = 12, max = 4096) val newPassword: String?,
)
