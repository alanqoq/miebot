package com.mieai.qqbot.persistence.inbox

import java.util.UUID

/** Indicates that an Inbox lease was lost before a fenced state transition. */
class InboxTransitionException(
    val eventId: UUID,
    val fencingToken: Long,
    val targetStatus: InboxStatus,
) : RuntimeException("Inbox event $eventId could not transition to $targetStatus with fencing token $fencingToken")
