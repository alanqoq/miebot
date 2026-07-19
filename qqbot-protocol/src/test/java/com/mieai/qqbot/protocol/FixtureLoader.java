package com.mieai.qqbot.protocol;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

final class FixtureLoader {
    private FixtureLoader() {}

    static String load(String resourcePath) {
        try (InputStream input = FixtureLoader.class.getResourceAsStream(resourcePath)) {
            if (input == null) {
                throw new IllegalArgumentException("Fixture not found: " + resourcePath);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read fixture: " + resourcePath, exception);
        }
    }
}
