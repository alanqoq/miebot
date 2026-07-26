package com.mieai.qqbot.admin.security

class LoginThrottledException(retryAfterSeconds: Long) : RuntimeException("Too many failed sign-in attempts") {
    val retryAfterSeconds = maxOf(1L, retryAfterSeconds)
}
