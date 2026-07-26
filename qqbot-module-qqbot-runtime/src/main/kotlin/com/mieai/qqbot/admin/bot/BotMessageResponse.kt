package com.mieai.qqbot.admin.bot

import java.time.Instant
import java.util.UUID

data class BotMessageResponse(val jobId: UUID, val alreadyPresent: Boolean, val queuedAt: Instant)
