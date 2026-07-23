package com.mieai.qqbot.onebot11.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class OneBotMessageCodecTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OneBotMessageCodec codec = new OneBotMessageCodec(objectMapper);

    @Test
    void parsesCqCodesAndPreservesArrayRepresentation() throws Exception {
        List<OneBotSegment> segments = codec.parse(objectMapper.readTree(
                "\"hello&#91;x&#93;[CQ:image,file=https://example.com/a.png]\""), false);

        assertThat(segments).containsExactly(
                new OneBotSegment("text", java.util.Map.of("text", "hello[x]")),
                new OneBotSegment("image", java.util.Map.of(
                        "file", "https://example.com/a.png")));
        assertThat(codec.toRawMessage(segments))
                .isEqualTo("hello&#91;x&#93;[CQ:image,file=https://example.com/a.png]");
        assertThat(codec.toArray(segments).get(1).path("type").asText()).isEqualTo("image");
    }

    @Test
    void autoEscapeTreatsCqTextLiterally() throws Exception {
        List<OneBotSegment> segments = codec.parse(
                objectMapper.readTree("\"[CQ:face,id=14]\""), true);
        assertThat(segments).containsExactly(
                new OneBotSegment("text", java.util.Map.of("text", "[CQ:face,id=14]")));
    }
}
