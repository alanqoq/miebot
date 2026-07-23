package com.mieai.qqbot.admin.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SetupAdminRequest(
        @NotBlank
        @Size(min = 3, max = 64)
        @Pattern(regexp = "[A-Za-z0-9._-]+")
        String username,
        @NotBlank @Size(min = 12, max = 72) String password) {
}
