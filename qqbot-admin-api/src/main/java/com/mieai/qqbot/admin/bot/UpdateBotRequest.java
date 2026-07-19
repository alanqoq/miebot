package com.mieai.qqbot.admin.bot;

import com.mieai.qqbot.domain.bot.BotEnvironment;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record UpdateBotRequest(
        @Positive long expectedRevision,
        @NotBlank @Size(max = 128) String displayName,
        @NotBlank @Size(max = 128) String appId,
        @NotNull BotEnvironment environment,
        @PositiveOrZero long intents,
        @Min(0) @Max(4095) int shardIndex,
        @Positive @Max(4096) int shardCount,
        @Size(min = 1, max = 4096) String appSecret) {
}
