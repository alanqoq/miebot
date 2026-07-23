package com.mieai.qqbot.app.onboarding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.Optional;

final class OnboardingStateStore {
    private final Path file;
    private final ObjectMapper objectMapper;
    private final ObjectWriter writer;

    OnboardingStateStore(Path file, ObjectMapper objectMapper) {
        this.file = Objects.requireNonNull(file, "file must not be null")
                .toAbsolutePath()
                .normalize();
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.writer = objectMapper.writerWithDefaultPrettyPrinter();
    }

    Optional<OnboardingFileState> load() {
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(file.toFile(), OnboardingFileState.class));
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("Unable to read onboarding state file", exception);
        }
    }

    void save(OnboardingFileState state) {
        Objects.requireNonNull(state, "state must not be null");
        Path parent = file.getParent();
        Path temporary = null;
        try {
            Files.createDirectories(parent);
            temporary = Files.createTempFile(parent, file.getFileName().toString(), ".pending");
            writer.writeValue(temporary.toFile(), state);
            moveAtomically(temporary, file);
            temporary = null;
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to save onboarding state file", exception);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // The committed state is unaffected; stale pending files are harmless.
                }
            }
        }
    }

    Path file() {
        return file;
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(
                    source,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
