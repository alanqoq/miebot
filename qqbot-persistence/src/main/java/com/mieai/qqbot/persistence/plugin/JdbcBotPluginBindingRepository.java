package com.mieai.qqbot.persistence.plugin;

import com.mieai.qqbot.domain.bot.BotId;
import com.mieai.qqbot.persistence.internal.UtcTimestampCodec;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcBotPluginBindingRepository implements BotPluginBindingRepository {
    private static final String COLUMNS = "id, plugin_id, bot_id, config_json, enabled, revision, created_at, updated_at";
    private final JdbcTemplate jdbc;

    public JdbcBotPluginBindingRepository(DataSource dataSource) {
        jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource must not be null"));
    }

    @Override
    public List<BotPluginBinding> findAll() {
        return List.copyOf(jdbc.query("SELECT " + COLUMNS + " FROM bot_plugins ORDER BY created_at, id",
                new BotPluginBindingRowMapper()));
    }

    @Override
    public List<BotPluginBinding> findEnabled() {
        return List.copyOf(jdbc.query("SELECT " + COLUMNS + " FROM bot_plugins WHERE enabled = 1 ORDER BY created_at, id",
                new BotPluginBindingRowMapper()));
    }

    @Override
    public List<BotPluginBinding> findByBotId(BotId botId) {
        Objects.requireNonNull(botId, "botId must not be null");
        return List.copyOf(jdbc.query("SELECT " + COLUMNS + " FROM bot_plugins WHERE bot_id = ? ORDER BY created_at, id",
                new BotPluginBindingRowMapper(), botId.toString()));
    }

    @Override
    public Optional<BotPluginBinding> findById(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM bot_plugins WHERE id = ?", new BotPluginBindingRowMapper(), id.toString())
                .stream().findFirst();
    }

    @Override
    public Optional<BotPluginBinding> findByPluginAndBot(String pluginId, BotId botId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM bot_plugins WHERE plugin_id = ? AND bot_id = ?",
                new BotPluginBindingRowMapper(), pluginId, botId.toString()).stream().findFirst();
    }

    @Override
    public void insert(BotPluginBinding binding) {
        Objects.requireNonNull(binding, "binding must not be null");
        jdbc.update("""
                INSERT INTO bot_plugins (id, plugin_id, bot_id, config_json, enabled, revision, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, binding.id().toString(), binding.pluginId(), binding.botId().toString(), binding.configJson(),
                binding.enabled() ? 1 : 0, binding.revision(), UtcTimestampCodec.format(binding.createdAt()),
                UtcTimestampCodec.format(binding.updatedAt()));
    }

    @Override
    public BotPluginBinding update(BotPluginBinding binding, long expectedRevision) {
        Objects.requireNonNull(binding, "binding must not be null");
        if (binding.revision() != expectedRevision) throw new IllegalArgumentException("binding revision mismatch");
        long next = Math.addExact(expectedRevision, 1L);
        int updated = jdbc.update("""
                UPDATE bot_plugins SET config_json=?, enabled=?, revision=?, updated_at=?
                WHERE id=? AND revision=?
                """, binding.configJson(), binding.enabled() ? 1 : 0, next,
                UtcTimestampCodec.format(binding.updatedAt()), binding.id().toString(), expectedRevision);
        if (updated == 0) throw new PluginBindingOptimisticLockException(binding.id(), expectedRevision);
        return new BotPluginBinding(binding.id(), binding.pluginId(), binding.botId(), binding.configJson(),
                binding.enabled(), next, binding.createdAt(), binding.updatedAt());
    }

    @Override
    public void delete(UUID id) {
        jdbc.update("DELETE FROM bot_plugins WHERE id = ?", id.toString());
    }
}
