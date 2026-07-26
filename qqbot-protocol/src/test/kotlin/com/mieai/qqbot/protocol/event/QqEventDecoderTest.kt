package com.mieai.qqbot.protocol.event

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test

class QqEventDecoderTest {
    private val decoder = QqEventDecoder.defaultDecoder()

    @Test
    fun decodesC2cMessagesIncludingOpenIdsReferencesAndAttachments() {
        val payload = """
            {"op":0,"s":12,"t":"C2C_MESSAGE_CREATE","d":{
              "id":"message-1","content":"hello","timestamp":"2026-07-22T12:00:00+08:00",
              "author":{"user_openid":"user-openid"},
              "message_reference":{"message_id":"quoted-1"},
              "attachments":[{"id":"asset-1","filename":"photo.png",
                "content_type":"image/png","url":"https://cdn.example/photo.png",
                "size":123,"width":640,"height":480}]
            }}
        """.trimIndent()

        val event = requireNotNull(decoder.decodeKnown("C2C_MESSAGE_CREATE", payload)) as QqEventModels.Message

        assertThat(event.id).isEqualTo("message-1")
        assertThat(event.author!!.userOpenId).isEqualTo("user-openid")
        assertThat(event.messageReference!!.messageId).isEqualTo("quoted-1")
        val attachment = requireNotNull(event.attachments).single()
        assertThat(attachment.contentType).isEqualTo("image/png")
        assertThat(attachment.width).isEqualTo(640)
    }

    @Test
    fun decodesNewGroupMemberAndInteractionEvents() {
        val memberPayload = """
            {"op":0,"s":13,"t":"GROUP_MEMBER_ADD","d":{
              "group_openid":"group-1","member_openid":"user-1","timestamp":1781680853
            }}
        """.trimIndent()
        val member = requireNotNull(decoder.decodeKnown("GROUP_MEMBER_ADD", memberPayload))
            as QqEventModels.GroupLifecycle

        val interactionPayload = """
            {"op":0,"s":14,"t":"INTERACTION_CREATE","d":{
              "id":"interaction-1","type":11,"scene":"group","chat_type":1,
              "group_openid":"group-1","group_member_openid":"user-1","version":1,
              "data":{"type":11,"resolved":{"button_data":"confirm","button_id":"21"}}
            }}
        """.trimIndent()
        val interaction = requireNotNull(decoder.decodeKnown("INTERACTION_CREATE", interactionPayload))
            as QqEventModels.Interaction

        assertThat(member.groupOpenId).isEqualTo("group-1")
        assertThat(member.memberOpenId).isEqualTo("user-1")
        assertThat(interaction.data!!.resolved!!.buttonData).isEqualTo("confirm")
        assertThat(interaction.groupMemberOpenId).isEqualTo("user-1")
    }

    @Test
    fun leavesUnknownEventsRawAndRejectsAnEventNameMismatch() {
        assertThat(decoder.decodeKnown("FUTURE_EVENT", "not parsed for unknown events")).isNull()

        val payload = "{\"op\":0,\"s\":1,\"t\":\"FRIEND_DEL\",\"d\":{}}"
        assertThatIllegalArgumentException()
            .isThrownBy { decoder.decodeKnown("FRIEND_ADD", payload) }
            .withMessageContaining("does not match")
    }
}
