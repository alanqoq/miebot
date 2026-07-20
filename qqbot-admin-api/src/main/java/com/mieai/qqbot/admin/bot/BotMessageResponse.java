package com.mieai.qqbot.admin.bot;

import java.time.Instant;
import java.util.UUID;

public record BotMessageResponse(UUID jobId, boolean alreadyPresent, Instant queuedAt) {}
