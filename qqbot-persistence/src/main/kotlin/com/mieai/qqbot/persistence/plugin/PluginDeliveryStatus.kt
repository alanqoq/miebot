package com.mieai.qqbot.persistence.plugin

enum class PluginDeliveryStatus {
    PENDING,
    IN_PROGRESS,
    RETRY_WAIT,
    SUCCEEDED,
    DEAD_LETTER,
    PAUSED,
    ;

    fun terminal(): Boolean = this == SUCCEEDED || this == DEAD_LETTER
}
