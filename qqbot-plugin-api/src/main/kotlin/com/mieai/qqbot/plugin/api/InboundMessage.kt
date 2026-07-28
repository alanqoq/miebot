package com.mieai.qqbot.plugin.api

/** Stable message fields extracted from the QQ Gateway payload. */
data class InboundMessage @JvmOverloads constructor(
    val replyTarget: MessageTarget,
    val messageId: String?,
    val eventId: String?,
    val authorId: String?,
    val content: String?,
    val referencedMessageId: String? = null,
    val memberRole: GroupMemberRole? = null,
) {
    @Deprecated("Retained for plugin API 3.0 binary compatibility", level = DeprecationLevel.HIDDEN)
    fun copy(
        replyTarget: MessageTarget,
        messageId: String?,
        eventId: String?,
        authorId: String?,
        content: String?,
        referencedMessageId: String?,
    ): InboundMessage = InboundMessage(
        replyTarget,
        messageId,
        eventId,
        authorId,
        content,
        referencedMessageId,
        memberRole,
    )

    companion object {
        @JvmStatic
        @Suppress("UNUSED_PARAMETER")
        @Deprecated("Retained for plugin API 3.0 binary compatibility", level = DeprecationLevel.HIDDEN)
        fun `copy$default`(
            source: InboundMessage,
            replyTarget: MessageTarget?,
            messageId: String?,
            eventId: String?,
            authorId: String?,
            content: String?,
            referencedMessageId: String?,
            mask: Int,
            marker: Any?,
        ): InboundMessage = InboundMessage(
            replyTarget = if (mask and 0x01 != 0) source.replyTarget else requireNotNull(replyTarget),
            messageId = if (mask and 0x02 != 0) source.messageId else messageId,
            eventId = if (mask and 0x04 != 0) source.eventId else eventId,
            authorId = if (mask and 0x08 != 0) source.authorId else authorId,
            content = if (mask and 0x10 != 0) source.content else content,
            referencedMessageId = if (mask and 0x20 != 0) source.referencedMessageId else referencedMessageId,
            memberRole = source.memberRole,
        )
    }
}
