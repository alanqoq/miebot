package com.mieai.qqbot.admin.onboarding

import org.springframework.http.HttpStatus

class OnboardingOperationException(
    val status: HttpStatus,
    val code: String,
    message: String?,
) : RuntimeException(message)
