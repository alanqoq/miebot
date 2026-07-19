package com.mieai.qqbot.persistence.plugin;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface PluginStorageRepository {
    Optional<String> find(UUID bindingId, String namespace, String key);
    void put(UUID bindingId, String namespace, String key, String value, Instant updatedAt);
    void delete(UUID bindingId, String namespace, String key);
    Map<String, String> list(UUID bindingId, String namespace);
}
