package com.mieai.qqbot.admin.bot

import java.time.Instant

data class BotRuntimeErrorResponse(val code: String, val message: String, val occurredAt: Instant)
