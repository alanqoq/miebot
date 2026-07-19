package com.mieai.qqbot.plugin.api;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MediaMessageTest {
    @Test
    void acceptsPublicHttpsAndRejectsLocalOrCredentialedUrls() {
        new MediaMessage(new MessageTarget(MessageTargetType.GROUP, "group-1"), MediaKind.IMAGE,
                URI.create("https://cdn.example/image.png?signature=ok"), Optional.empty(),
                Optional.empty(), Optional.empty(), 1, Optional.empty(), Optional.empty());

        assertThatThrownBy(() -> message("http://cdn.example/image.png"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> message("https://127.0.0.1/image.png"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> message("https://user:pass@cdn.example/image.png"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static MediaMessage message(String url) {
        return new MediaMessage(new MessageTarget(MessageTargetType.C2C, "user-1"), MediaKind.FILE,
                URI.create(url), Optional.empty(), Optional.empty(), Optional.empty(), 1,
                Optional.empty(), Optional.empty());
    }
}
