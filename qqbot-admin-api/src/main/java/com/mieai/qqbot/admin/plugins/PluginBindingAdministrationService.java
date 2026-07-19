package com.mieai.qqbot.admin.plugins;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mieai.qqbot.domain.bot.BotId;
import com.mieai.qqbot.persistence.bot.BotRepository;
import com.mieai.qqbot.persistence.plugin.BotPluginBinding;
import com.mieai.qqbot.persistence.plugin.BotPluginBindingRepository;
import com.mieai.qqbot.persistence.plugin.PluginArtifactRepository;
import com.mieai.qqbot.persistence.plugin.PluginBindingOptimisticLockException;
import com.mieai.qqbot.plugin.host.Pf4jPluginHost;
import com.mieai.qqbot.plugin.host.PluginRuntimeService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class PluginBindingAdministrationService {
    private final BotPluginBindingRepository bindings;
    private final PluginArtifactRepository artifacts;
    private final BotRepository bots;
    private final Pf4jPluginHost host;
    private final PluginRuntimeService runtime;
    private final ObjectMapper mapper;
    private final Clock clock;

    @Autowired
    public PluginBindingAdministrationService(BotPluginBindingRepository bindings,
            PluginArtifactRepository artifacts, BotRepository bots, Pf4jPluginHost host,
            PluginRuntimeService runtime, ObjectMapper mapper) {
        this(bindings, artifacts, bots, host, runtime, mapper, Clock.systemUTC());
    }

    PluginBindingAdministrationService(BotPluginBindingRepository bindings,
            PluginArtifactRepository artifacts, BotRepository bots, Pf4jPluginHost host,
            PluginRuntimeService runtime, ObjectMapper mapper, Clock clock) {
        this.bindings = bindings;
        this.artifacts = artifacts;
        this.bots = bots;
        this.host = host;
        this.runtime = runtime;
        this.mapper = mapper;
        this.clock = clock;
    }

    public List<PluginBindingResponse> list(String pluginId, String botId) {
        BotId parsedBot = botId == null || botId.isBlank() ? null : BotId.parse(botId);
        return bindings.findAll().stream()
                .filter(binding -> pluginId == null || pluginId.isBlank() || binding.pluginId().equals(pluginId))
                .filter(binding -> parsedBot == null || binding.botId().equals(parsedBot))
                .map(PluginBindingResponse::from).toList();
    }

    public PluginBindingResponse create(CreatePluginBindingRequest request) {
        BotId botId = BotId.parse(request.botId());
        if (bots.findById(botId).isEmpty()) throw notFound("BOT_NOT_FOUND", "Bot does not exist");
        if (artifacts.findById(request.pluginId()).isEmpty() || !host.isLoaded(request.pluginId())) {
            throw notFound("PLUGIN_NOT_LOADED", "Plugin is not loaded");
        }
        if (bindings.findByPluginAndBot(request.pluginId(), botId).isPresent()) {
            throw new PluginAdministrationException(HttpStatus.CONFLICT, "BINDING_EXISTS",
                    "This plugin is already bound to the bot");
        }
        String config = normalizedConfiguration(request.pluginId(), request.configJson());
        Instant now = clock.instant();
        BotPluginBinding binding = new BotPluginBinding(UUID.randomUUID(), request.pluginId(), botId,
                config, request.enabled(), 0L, now, now);
        bindings.insert(binding);
        runtime.bindingChanged(binding.id());
        return PluginBindingResponse.from(binding);
    }

    public PluginBindingResponse update(UUID id, UpdatePluginBindingRequest request) {
        BotPluginBinding current = bindings.findById(id).orElseThrow(
                () -> notFound("BINDING_NOT_FOUND", "Plugin binding does not exist"));
        String config = normalizedConfiguration(current.pluginId(), request.configJson());
        BotPluginBinding changed = new BotPluginBinding(current.id(), current.pluginId(), current.botId(),
                config, request.enabled(), request.expectedRevision(), current.createdAt(), clock.instant());
        try {
            BotPluginBinding saved = bindings.update(changed, request.expectedRevision());
            runtime.bindingChanged(id);
            return PluginBindingResponse.from(saved);
        } catch (PluginBindingOptimisticLockException exception) {
            throw new PluginAdministrationException(HttpStatus.CONFLICT, "REVISION_CONFLICT", exception.getMessage());
        }
    }

    public void delete(UUID id) {
        if (bindings.findById(id).isEmpty()) throw notFound("BINDING_NOT_FOUND", "Plugin binding does not exist");
        runtime.bindingChanged(id);
        bindings.delete(id);
    }

    private String normalizedConfiguration(String pluginId, String value) {
        try {
            JsonNode parsed = mapper.readTree(value);
            if (parsed == null || !parsed.isObject()) {
                throw new PluginAdministrationException(HttpStatus.BAD_REQUEST, "INVALID_PLUGIN_CONFIG",
                        "Plugin configuration must be a JSON object");
            }
            String normalized = mapper.writeValueAsString(parsed);
            List<String> violations = host.validateConfiguration(pluginId, normalized);
            if (!violations.isEmpty()) {
                throw new PluginAdministrationException(HttpStatus.BAD_REQUEST, "INVALID_PLUGIN_CONFIG",
                        violations.getFirst());
            }
            return normalized;
        } catch (JsonProcessingException exception) {
            throw new PluginAdministrationException(HttpStatus.BAD_REQUEST, "INVALID_PLUGIN_CONFIG",
                    "Plugin configuration is not valid JSON");
        }
    }

    private static PluginAdministrationException notFound(String code, String message) {
        return new PluginAdministrationException(HttpStatus.NOT_FOUND, code, message);
    }
}
