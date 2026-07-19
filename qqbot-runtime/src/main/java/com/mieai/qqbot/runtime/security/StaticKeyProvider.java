package com.mieai.qqbot.runtime.security;

import java.util.Objects;
import java.util.Optional;
import javax.crypto.spec.SecretKeySpec;

/** Single-master-key provider suitable for application configuration and local deployments. */
public final class StaticKeyProvider implements KeyProvider {
    private final Optional<KeyMaterial> keyMaterial;

    private StaticKeyProvider(Optional<KeyMaterial> keyMaterial) {
        this.keyMaterial = keyMaterial;
    }

    public static StaticKeyProvider configured(String keyId, byte[] keyBytes) {
        Objects.requireNonNull(keyBytes, "keyBytes must not be null");
        return new StaticKeyProvider(Optional.of(
                new KeyMaterial(keyId, new SecretKeySpec(keyBytes, "AES"))));
    }

    public static StaticKeyProvider unconfigured() {
        return new StaticKeyProvider(Optional.empty());
    }

    public boolean isConfigured() {
        return keyMaterial.isPresent();
    }

    @Override
    public KeyMaterial activeKey() {
        return keyMaterial.orElseThrow(KeyUnavailableException::new);
    }

    @Override
    public Optional<KeyMaterial> findById(String keyId) {
        Objects.requireNonNull(keyId, "keyId must not be null");
        if (keyMaterial.isEmpty()) {
            throw new KeyUnavailableException();
        }
        return keyMaterial.filter(material -> material.keyId().equals(keyId));
    }

    @Override
    public String toString() {
        return "StaticKeyProvider[configured=" + keyMaterial.isPresent() + ", key=<redacted>]";
    }
}
