package com.mieai.qqbot.admin.plugins

import org.springframework.http.HttpStatus

class PluginAdministrationException(
    val status: HttpStatus,
    val code: String,
    message: String?,
) : RuntimeException(message)
