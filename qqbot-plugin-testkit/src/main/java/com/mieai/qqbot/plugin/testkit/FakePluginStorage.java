package com.mieai.qqbot.plugin.testkit;

import com.mieai.qqbot.plugin.api.PluginStorage;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** In-memory binding-local storage with the same public size limits as the production capability. */
public final class FakePluginStorage implements PluginStorage {
    private final Map<String, Map<String, String>> values = new LinkedHashMap<>();

    @Override
    public synchronized Optional<String> get(String namespace, String key) {
        validate(namespace, key);
        return Optional.ofNullable(values.getOrDefault(namespace, Map.of()).get(key));
    }

    @Override
    public synchronized void put(String namespace, String key, String value) {
        validate(namespace, key);
        if (value == null || value.getBytes(StandardCharsets.UTF_8).length > MAX_VALUE_BYTES) {
            throw new IllegalArgumentException("value exceeds the storage limit");
        }
        values.computeIfAbsent(namespace, ignored -> new LinkedHashMap<>()).put(key, value);
    }

    @Override
    public synchronized void delete(String namespace, String key) {
        validate(namespace, key);
        Map<String, String> namespaceValues = values.get(namespace);
        if (namespaceValues != null) namespaceValues.remove(key);
    }

    @Override
    public synchronized Map<String, String> list(String namespace) {
        validateToken(namespace, MAX_NAMESPACE_LENGTH, "namespace");
        return Map.copyOf(values.getOrDefault(namespace, Map.of()));
    }

    private static void validate(String namespace, String key) {
        validateToken(namespace, MAX_NAMESPACE_LENGTH, "namespace");
        validateToken(key, MAX_KEY_LENGTH, "key");
    }

    private static void validateToken(String value, int maximum, String name) {
        if (value == null || value.isBlank() || value.length() > maximum
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " is invalid");
        }
    }
}
