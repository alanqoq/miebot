package com.mieai.qqbot.plugin.api;

import java.util.Arrays;
import java.util.Objects;

/** Bounded local media bytes supplied by a plugin to the host staging store. */
public record MediaUpload(MediaKind kind, String fileName, String contentType, byte[] data) {
    public MediaUpload {
        Objects.requireNonNull(kind, "kind must not be null");
        if (fileName == null || fileName.isBlank() || fileName.length() > 255) throw new IllegalArgumentException("fileName is invalid");
        if (contentType == null || contentType.isBlank() || contentType.length() > 128) throw new IllegalArgumentException("contentType is invalid");
        byte[] supplied = Objects.requireNonNull(data, "data must not be null");
        if (supplied.length == 0) throw new IllegalArgumentException("data must not be empty");
        if (supplied.length > 256L * 1024L * 1024L) {
            throw new IllegalArgumentException("data must not exceed 256 MiB");
        }
        data = supplied.clone();
    }

    @Override public byte[] data() { return data.clone(); }
}
