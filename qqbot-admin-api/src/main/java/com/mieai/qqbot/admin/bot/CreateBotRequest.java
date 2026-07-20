package com.mieai.qqbot.admin.bot;

import com.mieai.qqbot.domain.bot.BotEnvironment;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record CreateBotRequest(
        @NotBlank @Size(max = 128) String displayName,
        @NotBlank @Size(max = 128) String appId,
        @NotNull BotEnvironment environment,
        @PositiveOrZero long intents,
        @Min(0) @Max(4095) int shardIndex,
        @Positive @Max(4096) int shardCount,
        boolean enabled,
        @NotBlank @Size(max = 4096) String appSecret,
        @Min(1048576) @Max(268435456) Long maxMediaUploadBytes) {

    public CreateBotRequest {
        if (maxMediaUploadBytes == null) maxMediaUploadBytes = 16L * 1024L * 1024L;
    }

    public CreateBotRequest(String displayName, String appId, BotEnvironment environment,
            long intents, int shardIndex, int shardCount, boolean enabled, String appSecret) {
        this(displayName, appId, environment, intents, shardIndex, shardCount, enabled, appSecret,
                16L * 1024L * 1024L);
    }
}
