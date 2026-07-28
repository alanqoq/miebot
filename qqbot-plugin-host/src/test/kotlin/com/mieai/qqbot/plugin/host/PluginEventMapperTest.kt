package com.mieai.qqbot.plugin.host

import com.fasterxml.jackson.databind.ObjectMapper
import com.mieai.qqbot.domain.bot.BotEnvironment
import com.mieai.qqbot.domain.bot.BotId
import com.mieai.qqbot.persistence.inbox.InboxEvent
import com.mieai.qqbot.persistence.inbox.InboxStatus
import com.mieai.qqbot.plugin.api.GroupMemberRole
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class PluginEventMapperTest {
    @Test
    fun exposesReferencedPlatformMessageIdInStableInboundMessage() {
        val receivedAt = Instant.parse("2026-07-24T12:00:00Z")
        val event = InboxEvent(
            UUID.fromString("10000000-0000-0000-0000-000000000001"),
            BotEnvironment.SANDBOX,
            BotId.parse("550e8400-e29b-41d4-a716-446655440001"),
            "GROUP_AT_MESSAGE_CREATE",
            "event-1",
            """
                {"id":"event-1","d":{"id":"user-message-1","group_openid":"group-1",
                "content":"quoted","author":{"member_openid":"user-1","member_role":"admin"},
                "message_reference":{"message_id":"bot-message-900"}}}
            """.trimIndent(),
            InboxStatus.RECEIVED,
            0,
            receivedAt,
            null,
            null,
            0L,
            null,
            receivedAt,
            receivedAt,
        )

        val message = requireNotNull(PluginEventMapper(ObjectMapper()).map(event).message)

        assertThat(message.messageId).isEqualTo("user-message-1")
        assertThat(message.referencedMessageId).isEqualTo("bot-message-900")
        assertThat(message.memberRole).isEqualTo(GroupMemberRole.ADMIN)
    }
}
