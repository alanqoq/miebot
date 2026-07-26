package com.mieai.qqbot.admin.bot

import jakarta.validation.constraints.Positive

data class SetBotEnabledRequest(@field:Positive val expectedRevision: Long, val enabled: Boolean)
