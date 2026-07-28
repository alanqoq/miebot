package com.mieai.qqbot.plugin.api

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class MessageReferenceTest {
    @Test
    fun acceptsAPlatformMessageIdAndUsesStrictFailureByDefault() {
        val reference = MessageReference("bot-message-900")

        assertThat(reference.messageId).isEqualTo("bot-message-900")
        assertThat(reference.ignoreGetMessageError).isFalse()
    }

    @Test
    fun rejectsBlankOrWhitespaceContainingMessageIds() {
        assertThatThrownBy { MessageReference(" ") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { MessageReference("message 1") }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
