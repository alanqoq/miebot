package com.mieai.qqbot.plugin.api

import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.net.URI

class MediaMessageTest {
    @Test
    fun acceptsPublicHttpsAndRejectsLocalOrCredentialedUrls() {
        MediaMessage(
            MessageTarget(MessageTargetType.GROUP, "group-1"),
            MediaKind.IMAGE,
            URI.create("https://cdn.example/image.png?signature=ok"),
        )

        assertThatThrownBy { message("http://cdn.example/image.png") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { message("https://127.0.0.1/image.png") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { message("https://user:pass@cdn.example/image.png") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun message(url: String) = MediaMessage(
        MessageTarget(MessageTargetType.C2C, "user-1"),
        MediaKind.FILE,
        URI.create(url),
    )
}
