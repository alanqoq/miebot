package com.mieai.qqbot.onebot11;

import com.mieai.qqbot.domain.bot.BotId;
import com.mieai.qqbot.onebot11.config.OneBot11ConfigurationService;
import com.mieai.qqbot.onebot11.config.OneBot11SettingsRequest;
import com.mieai.qqbot.onebot11.config.OneBot11SettingsView;
import com.mieai.qqbot.onebot11.transport.OneBotRuntimeManager;
import com.mieai.qqbot.onebot11.transport.OneBotTransportStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/bots/{botId}/onebot11")
public class OneBot11SettingsController {
    private final OneBot11ConfigurationService configurations;
    private final OneBotRuntimeManager runtimes;

    public OneBot11SettingsController(
            OneBot11ConfigurationService configurations,
            OneBotRuntimeManager runtimes) {
        this.configurations = configurations;
        this.runtimes = runtimes;
    }

    @GetMapping
    public OneBot11SettingsResponse get(@PathVariable String botId) {
        BotId id = BotId.parse(botId);
        return response(configurations.get(id), runtimes.status(id));
    }

    @PutMapping
    public OneBot11SettingsResponse update(
            @PathVariable String botId,
            @RequestBody OneBot11SettingsRequest request) {
        BotId id = BotId.parse(botId);
        OneBot11SettingsView saved = configurations.update(id, request);
        runtimes.reconcile(id);
        return response(saved, runtimes.status(id));
    }

    private static OneBot11SettingsResponse response(
            OneBot11SettingsView settings, OneBotTransportStatus runtime) {
        return new OneBot11SettingsResponse(settings, runtime);
    }

    public record OneBot11SettingsResponse(
            OneBot11SettingsView settings,
            OneBotTransportStatus runtime) {}
}
