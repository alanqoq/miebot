package com.mieai.qqbot.module.host;

import com.mieai.qqbot.module.api.ModuleDescriptor;
import java.nio.file.Path;
import java.util.Objects;

/** One validated framework module JAR and the descriptor owned by it. */
public record ModuleArtifact(Path path, ModuleDescriptor descriptor, String sha256) {
    public ModuleArtifact {
        path = Objects.requireNonNull(path, "path must not be null").toAbsolutePath().normalize();
        descriptor = Objects.requireNonNull(descriptor, "descriptor must not be null");
        sha256 = Objects.requireNonNull(sha256, "sha256 must not be null");
        if (!sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("sha256 is invalid");
        }
    }
}
