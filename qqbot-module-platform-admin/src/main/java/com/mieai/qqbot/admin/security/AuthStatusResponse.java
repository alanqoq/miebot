package com.mieai.qqbot.admin.security;

public record AuthStatusResponse(
        boolean setupRequired,
        boolean authenticated,
        String username) {
}
