package com.mieai.qqbot.admin.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChangePasswordRequest(
        @NotBlank @Size(max = 4096) String currentPassword,
        @NotBlank @Size(min = 12, max = 4096) String newPassword) {
}
