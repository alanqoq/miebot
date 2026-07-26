package com.mieai.qqbot.admin.security


data class AuthStatusResponse(
    val setupRequired: Boolean,
    val authenticated: Boolean,
    val username: String?,
)
