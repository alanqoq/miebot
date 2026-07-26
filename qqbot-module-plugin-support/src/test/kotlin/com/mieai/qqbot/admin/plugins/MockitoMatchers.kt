package com.mieai.qqbot.admin.plugins

import org.mockito.ArgumentMatchers

internal fun <T : Any> matchAny(type: Class<T>, fallback: T): T =
    ArgumentMatchers.any(type) ?: fallback

internal fun <T : Any> matchEq(value: T): T =
    ArgumentMatchers.eq(value) ?: value
