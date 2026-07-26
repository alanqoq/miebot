package com.mieai.qqbot.admin.events

import com.mieai.qqbot.domain.bot.BotEnvironment
import com.mieai.qqbot.domain.bot.BotId
import com.mieai.qqbot.persistence.bot.BotRepository
import com.mieai.qqbot.persistence.inbox.EventInboxRepository
import com.mieai.qqbot.persistence.inbox.InboxEvent
import com.mieai.qqbot.persistence.inbox.InboxStatus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID

class InboxAdministrationServiceTest {
    private val inboxRepository = mock(EventInboxRepository::class.java)
    private val botRepository = mock(BotRepository::class.java)
    private val service = InboxAdministrationService(inboxRepository, botRepository)

    @Test
    fun `truncates oversized UTF-8 payload in linear code point boundaries`() {
        val emoji = "\uD83D\uDE00"
        val fittingCodePoints = InboxAdministrationService.MAX_DETAIL_PAYLOAD_BYTES / 4
        val payload = emoji.repeat(fittingCodePoints + 1)
        `when`(inboxRepository.findById(EVENT_ID)).thenReturn(event(payload))
        `when`(botRepository.findAll()).thenReturn(emptyList())

        val response = service.get(EVENT_ID.toString())

        assertThat(response.payloadTruncated).isTrue()
        assertThat(response.payload.toByteArray(StandardCharsets.UTF_8))
            .hasSize(InboxAdministrationService.MAX_DETAIL_PAYLOAD_BYTES)
        assertThat(response.payload.codePointCount(0, response.payload.length))
            .isEqualTo(fittingCodePoints)
        assertThat(response.payload).endsWith(emoji)
    }

    @Test
    fun `rejects limit above the public maximum before querying persistence`() {
        assertThatThrownBy { service.list("101", null, null, null, null, null, null) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("limit must be between 1 and 100")

        verifyNoInteractions(inboxRepository, botRepository)
    }

    private fun event(payload: String): InboxEvent = InboxEvent(
        EVENT_ID,
        BotEnvironment.PRODUCTION,
        BOT_ID,
        "MESSAGE_CREATE",
        "platform-event-1",
        payload,
        InboxStatus.RECEIVED,
        0,
        RECEIVED_AT,
        null,
        null,
        0L,
        null,
        RECEIVED_AT,
        RECEIVED_AT,
    )

    companion object {
        private val EVENT_ID: UUID = UUID.fromString("11000000-0000-0000-0000-000000000001")
        private val BOT_ID: BotId = BotId.parse("550e8400-e29b-41d4-a716-446655440001")
        private val RECEIVED_AT: Instant = Instant.parse("2026-07-18T06:14:58Z")
    }
}
