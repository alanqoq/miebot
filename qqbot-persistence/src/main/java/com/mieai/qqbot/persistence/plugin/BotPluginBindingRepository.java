package com.mieai.qqbot.persistence.plugin;

import com.mieai.qqbot.domain.bot.BotId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BotPluginBindingRepository {
    List<BotPluginBinding> findAll();
    List<BotPluginBinding> findEnabled();
    List<BotPluginBinding> findByBotId(BotId botId);
    Optional<BotPluginBinding> findById(UUID id);
    Optional<BotPluginBinding> findByPluginAndBot(String pluginId, BotId botId);
    void insert(BotPluginBinding binding);
    BotPluginBinding update(BotPluginBinding binding, long expectedRevision);
    void delete(UUID id);
}
