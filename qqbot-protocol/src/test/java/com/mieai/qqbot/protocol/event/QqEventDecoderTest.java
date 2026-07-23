package com.mieai.qqbot.protocol.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class QqEventDecoderTest {
    private final QqEventDecoder decoder = QqEventDecoder.defaultDecoder();

    @Test
    void decodesC2cMessagesIncludingOpenIdsReferencesAndAttachments() {
        String payload = """
                {"op":0,"s":12,"t":"C2C_MESSAGE_CREATE","d":{
                  "id":"message-1","content":"hello","timestamp":"2026-07-22T12:00:00+08:00",
                  "author":{"user_openid":"user-openid"},
                  "message_reference":{"message_id":"quoted-1"},
                  "attachments":[{"id":"asset-1","filename":"photo.png",
                    "content_type":"image/png","url":"https://cdn.example/photo.png",
                    "size":123,"width":640,"height":480}]
                }}
                """;

        QqEventModels.Message event = (QqEventModels.Message) decoder
                .decodeKnown("C2C_MESSAGE_CREATE", payload).orElseThrow();

        assertThat(event.id()).isEqualTo("message-1");
        assertThat(event.author().userOpenId()).isEqualTo("user-openid");
        assertThat(event.messageReference().messageId()).isEqualTo("quoted-1");
        assertThat(event.attachments()).singleElement().satisfies(attachment -> {
            assertThat(attachment.contentType()).isEqualTo("image/png");
            assertThat(attachment.width()).isEqualTo(640);
        });
    }

    @Test
    void decodesNewGroupMemberAndInteractionEvents() {
        String memberPayload = """
                {"op":0,"s":13,"t":"GROUP_MEMBER_ADD","d":{
                  "group_openid":"group-1","member_openid":"user-1","timestamp":1781680853
                }}
                """;
        QqEventModels.GroupLifecycle member = (QqEventModels.GroupLifecycle) decoder
                .decodeKnown("GROUP_MEMBER_ADD", memberPayload).orElseThrow();

        String interactionPayload = """
                {"op":0,"s":14,"t":"INTERACTION_CREATE","d":{
                  "id":"interaction-1","type":11,"scene":"group","chat_type":1,
                  "group_openid":"group-1","group_member_openid":"user-1","version":1,
                  "data":{"type":11,"resolved":{"button_data":"confirm","button_id":"21"}}
                }}
                """;
        QqEventModels.Interaction interaction = (QqEventModels.Interaction) decoder
                .decodeKnown("INTERACTION_CREATE", interactionPayload).orElseThrow();

        assertThat(member.groupOpenId()).isEqualTo("group-1");
        assertThat(member.memberOpenId()).isEqualTo("user-1");
        assertThat(interaction.data().resolved().buttonData()).isEqualTo("confirm");
        assertThat(interaction.groupMemberOpenId()).isEqualTo("user-1");
    }

    @Test
    void leavesUnknownEventsRawAndRejectsAnEventNameMismatch() {
        assertThat(decoder.decodeKnown("FUTURE_EVENT", "not parsed for unknown events")).isEmpty();

        String payload = "{\"op\":0,\"s\":1,\"t\":\"FRIEND_DEL\",\"d\":{}}";
        assertThatIllegalArgumentException()
                .isThrownBy(() -> decoder.decodeKnown("FRIEND_ADD", payload))
                .withMessageContaining("does not match");
    }
}
