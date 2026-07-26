package com.mieai.qqbot.admin.events

import com.mieai.qqbot.domain.bot.BotEnvironment
import com.mieai.qqbot.domain.bot.BotId
import com.mieai.qqbot.persistence.bot.BotRepository
import com.mieai.qqbot.persistence.outbox.OutboxJob
import com.mieai.qqbot.persistence.outbox.OutboxPage
import com.mieai.qqbot.persistence.outbox.OutboxQuery
import com.mieai.qqbot.persistence.outbox.OutboxQueueStats
import com.mieai.qqbot.persistence.outbox.OutboxRepository
import com.mieai.qqbot.persistence.outbox.OutboxStatus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID

class OutboxAdministrationServiceTest {
    private val outboxRepository = mock(OutboxRepository::class.java)
    private val botRepository = mock(BotRepository::class.java)
    private val service = OutboxAdministrationService(outboxRepository, botRepository)

    @Test
    fun `DLQ always builds a dead letter query`() {
        `when`(outboxRepository.query(anyQuery())).thenReturn(OutboxPage(emptyList(), null))
        `when`(outboxRepository.statistics()).thenReturn(OutboxQueueStats(0, 0, 0, 0, 0, 0, 0))
        `when`(botRepository.findAll()).thenReturn(emptyList())

        service.list("50", null, null, null, null, null, null, true)

        verify(outboxRepository).query(
            OutboxQuery(
                50,
                null,
                null,
                null,
                OutboxStatus.DEAD_LETTER,
                null,
                null,
            ),
        )
    }

    @Test
    fun `rejects non-dead-letter status before querying persistence`() {
        assertThatThrownBy {
            service.list("50", null, null, null, null, "PENDING", null, true)
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("status must be DEAD_LETTER for the DLQ view")

        verifyNoInteractions(outboxRepository, botRepository)
    }

    @Test
    fun `truncates detail payload by UTF-8 bytes without returning lease owner`() {
        val emoji = "\uD83D\uDE00"
        val payload = emoji.repeat(OutboxAdministrationService.MAX_DETAIL_PAYLOAD_BYTES / 4 + 1)
        `when`(outboxRepository.findById(JOB_ID)).thenReturn(job(payload))
        `when`(botRepository.findAll()).thenReturn(emptyList())

        val response = service.get(JOB_ID.toString())

        assertThat(response.payloadTruncated).isTrue()
        assertThat(response.payload.toByteArray(StandardCharsets.UTF_8))
            .hasSize(OutboxAdministrationService.MAX_DETAIL_PAYLOAD_BYTES)
        assertThat(response.leaseUntil).isNull()
    }

    @Test
    fun `omits deduplication key from list but returns it in detail`() {
        `when`(outboxRepository.query(anyQuery())).thenReturn(
            OutboxPage(listOf(job("{}")), null),
        )
        `when`(outboxRepository.statistics()).thenReturn(OutboxQueueStats(1, 1, 0, 0, 0, 0, 0))
        `when`(outboxRepository.findById(JOB_ID)).thenReturn(job("{}"))
        `when`(botRepository.findAll()).thenReturn(emptyList())

        val page = service.list("50", null, null, null, null, null, null)
        val detail = service.get(JOB_ID.toString())

        val summary = page.items.single()
        assertThat(summary.javaClass.declaredFields.map { it.name })
            .doesNotContain("dedupKey", "payload", "leaseOwner", "fencingToken")
        assertThat(detail.dedupKey).isEqualTo("reply:1")
    }

    @Test
    fun `returns persistent delivery receipt in detail`() {
        val bindingId = UUID.fromString("65000000-0000-0000-0000-000000000001")
        val succeeded = OutboxJob(
            JOB_ID,
            BotEnvironment.PRODUCTION,
            BOT_ID,
            null,
            "SEND_MESSAGE",
            "reply:1",
            "{}",
            OutboxStatus.SUCCEEDED,
            1,
            CREATED_AT,
            null,
            null,
            1,
            null,
            CREATED_AT,
            CREATED_AT,
            CREATED_AT,
            bindingId,
            "bot-message-900",
            3,
            "2026-07-24T12:00:00Z",
        )
        `when`(outboxRepository.findById(JOB_ID)).thenReturn(succeeded)
        `when`(botRepository.findAll()).thenReturn(emptyList())

        val detail = service.get(JOB_ID.toString())

        assertThat(detail.producerBindingId).isEqualTo(bindingId)
        assertThat(detail.platformMessageId).isEqualTo("bot-message-900")
        assertThat(detail.platformMessageSequence).isEqualTo(3)
        assertThat(detail.platformTimestamp).isEqualTo("2026-07-24T12:00:00Z")
    }

    private fun anyQuery(): OutboxQuery =
        ArgumentMatchers.any(OutboxQuery::class.java) ?: OutboxQuery.firstPage(1)

    private fun job(payload: String) = OutboxJob(
        JOB_ID,
        BotEnvironment.PRODUCTION,
        BOT_ID,
        null,
        "SEND_MESSAGE",
        "reply:1",
        payload,
        OutboxStatus.PENDING,
        0,
        CREATED_AT,
        null,
        null,
        0,
        null,
        CREATED_AT,
        CREATED_AT,
        null,
        null,
        null,
        null,
        null,
    )

    companion object {
        private val JOB_ID: UUID = UUID.fromString("41000000-0000-0000-0000-000000000001")
        private val BOT_ID: BotId = BotId.parse("550e8400-e29b-41d4-a716-446655440001")
        private val CREATED_AT: Instant = Instant.parse("2026-07-18T06:14:58Z")
    }
}
