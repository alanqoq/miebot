package com.mieai.qqbot.admin.bot;

import jakarta.validation.constraints.Positive;

public record SetBotEnabledRequest(@Positive long expectedRevision, boolean enabled) {
}
