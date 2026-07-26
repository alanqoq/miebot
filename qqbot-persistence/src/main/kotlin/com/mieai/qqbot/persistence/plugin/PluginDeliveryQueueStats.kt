package com.mieai.qqbot.persistence.plugin


/** Point-in-time lifecycle counts for plugin deliveries. */
data class PluginDeliveryQueueStats(
    val totalCount: Long,
    val pendingCount: Long,
    val inProgressCount: Long,
    val retryWaitCount: Long,
    val succeededCount: Long,
    val deadLetterCount: Long,
    val pausedCount: Long,
) {
    init {
        require(totalCount >= 0 && pendingCount >= 0 && inProgressCount >= 0 && retryWaitCount >= 0 &&
            succeededCount >= 0 && deadLetterCount >= 0 && pausedCount >= 0) {
            "plugin delivery counts must not be negative"
        }
        require(pendingCount + inProgressCount + retryWaitCount + succeededCount + deadLetterCount + pausedCount == totalCount) {
            "plugin delivery status counts must add up to totalCount"
        }
    }
}
