package com.mieai.qqbot.plugin.api

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class InboundMessageCompatibilityTest {
    @Test
    fun retainsApi30CopyMethodDescriptorsAndMemberRole() {
        val original = message()
        val copy = InboundMessage::class.java.getDeclaredMethod(
            "copy",
            MessageTarget::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
        )

        val copied = copy.invoke(
            original,
            original.replyTarget,
            original.messageId,
            original.eventId,
            original.authorId,
            "updated",
            original.referencedMessageId,
        ) as InboundMessage

        assertThat(copied.content).isEqualTo("updated")
        assertThat(copied.memberRole).isEqualTo(GroupMemberRole.OWNER)
    }

    @Test
    fun retainsApi30DefaultCopyDescriptorAndMaskBehavior() {
        val original = message()
        val copyDefault = InboundMessage::class.java.getDeclaredMethod(
            "copy\$default",
            InboundMessage::class.java,
            MessageTarget::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            Int::class.javaPrimitiveType,
            Any::class.java,
        )

        val copied = copyDefault.invoke(
            null,
            original,
            null,
            null,
            null,
            null,
            "updated",
            null,
            0x01 or 0x02 or 0x04 or 0x08 or 0x20,
            null,
        ) as InboundMessage

        assertThat(copied.replyTarget).isEqualTo(original.replyTarget)
        assertThat(copied.content).isEqualTo("updated")
        assertThat(copied.memberRole).isEqualTo(GroupMemberRole.OWNER)
    }

    private fun message(): InboundMessage = InboundMessage(
        replyTarget = MessageTarget(MessageTargetType.GROUP, "group-1"),
        messageId = "message-1",
        eventId = "event-1",
        authorId = "member-1",
        content = "original",
        referencedMessageId = "message-0",
        memberRole = GroupMemberRole.OWNER,
    )
}
