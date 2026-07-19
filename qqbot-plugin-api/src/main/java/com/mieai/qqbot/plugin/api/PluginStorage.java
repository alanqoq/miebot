package com.mieai.qqbot.plugin.api;

import java.util.Map;
import java.util.Optional;

/** Binding-scoped key/value storage; implementations must never expose arbitrary SQL. */
public interface PluginStorage {
    int MAX_NAMESPACE_LENGTH = 64;
    int MAX_KEY_LENGTH = 128;
    int MAX_VALUE_BYTES = 64 * 1024;

    Optional<String> get(String namespace, String key);
    void put(String namespace, String key, String value);
    void delete(String namespace, String key);
    Map<String, String> list(String namespace);

    static PluginStorage denied() {
        return new PluginStorage() {
            @Override public Optional<String> get(String namespace, String key) {
                throw new SecurityException("Plugin storage capability is not granted");
            }
            @Override public void put(String namespace, String key, String value) {
                throw new SecurityException("Plugin storage capability is not granted");
            }
            @Override public void delete(String namespace, String key) {
                throw new SecurityException("Plugin storage capability is not granted");
            }
            @Override public Map<String, String> list(String namespace) {
                throw new SecurityException("Plugin storage capability is not granted");
            }
        };
    }
}
