package com.mieai.qqbot.runtime.security;

import java.util.Optional;

/** Supplies the active master key and keys retained for decrypting older envelopes. */
public interface KeyProvider {
    KeyMaterial activeKey();

    Optional<KeyMaterial> findById(String keyId);
}
