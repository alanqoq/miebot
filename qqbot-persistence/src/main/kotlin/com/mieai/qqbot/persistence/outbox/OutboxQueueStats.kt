package com.mieai.qqbot.persistence.outbox


/** Point-in-time counts used by operations views; no job payload is included. */
data class OutboxQueueStats(
    val totalCount: Long,
    val pendingCount: Long,
    val inProgressCount: Long,
    val retryWaitCount: Long,
    val succeededCount: Long,
    val resultUnknownCount: Long,
    val deadLetterCount: Long,
) {
    init {
        require(totalCount >= 0 && pendingCount >= 0 && inProgressCount >= 0 && retryWaitCount >= 0 &&
            succeededCount >= 0 && resultUnknownCount >= 0 && deadLetterCount >= 0) {
            "Outbox counts must not be negative"
        }
        val sum = pendingCount + inProgressCount + retryWaitCount + succeededCount + resultUnknownCount + deadLetterCount
        require(sum == totalCount) { "Outbox status counts must add up to totalCount" }
    }
}
