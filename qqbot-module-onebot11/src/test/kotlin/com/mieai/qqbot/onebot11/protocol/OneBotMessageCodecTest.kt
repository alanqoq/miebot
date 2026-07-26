package com.mieai.qqbot.onebot11.protocol

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OneBotMessageCodecTest {
    private val objectMapper = ObjectMapper()
    private val codec = OneBotMessageCodec(objectMapper)

    @Test
    fun `parses CQ codes and preserves array representation`() {
        val segments = codec.parse(
            objectMapper.readTree("\"hello&#91;x&#93;[CQ:image,file=https://example.com/a.png]\""),
            false,
        )

        assertThat(segments).containsExactly(
            OneBotSegment("text", mapOf("text" to "hello[x]")),
            OneBotSegment("image", mapOf("file" to "https://example.com/a.png")),
        )
        assertThat(codec.toRawMessage(segments))
            .isEqualTo("hello&#91;x&#93;[CQ:image,file=https://example.com/a.png]")
        assertThat(codec.toArray(segments)[1].path("type").asText()).isEqualTo("image")
    }

    @Test
    fun `auto escape treats CQ text literally`() {
        val segments = codec.parse(objectMapper.readTree("\"[CQ:face,id=14]\""), true)
        assertThat(segments).containsExactly(
            OneBotSegment("text", mapOf("text" to "[CQ:face,id=14]")),
        )
    }
}
