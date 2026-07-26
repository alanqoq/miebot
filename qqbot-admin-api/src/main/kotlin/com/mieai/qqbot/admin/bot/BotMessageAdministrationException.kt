package com.mieai.qqbot.admin.bot

import org.springframework.http.HttpStatus

class BotMessageAdministrationException(
    val status: HttpStatus,
    val code: String,
    message: String?,
) : RuntimeException(message)
