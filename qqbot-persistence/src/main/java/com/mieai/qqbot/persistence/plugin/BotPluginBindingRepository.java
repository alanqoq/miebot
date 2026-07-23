package com.mieai.qqbot.persistence.plugin;

import com.mieai.qqbot.domain.bot.BotId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;

public interface BotPluginBindingRepository {
    List<BotPluginBinding> findAll();
    List<BotPluginBinding> findEnabled();
    List<BotPluginBinding> findByBotId(BotId botId);
    Optional<BotPluginBinding> findById(UUID id);
    Optional<BotPluginBinding> findByPluginAndBot(String pluginId, BotId botId);
    void insert(BotPluginBinding binding);
    BotPluginBinding update(BotPluginBinding binding, long expectedRevision);
    BotPluginBinding touch(UUID id, long expectedRevision, Instant now);
    void setRuntimeState(UUID id, PluginBindingRuntimeState state, String error, Instant now);
    void delete(UUID id);
}
