package com.mieai.qqbot.persistence.plugin

enum class PluginBindingRuntimeState {
    ACTIVE,
    PAUSED,
    QUARANTINED,
    ;

    fun runnable(): Boolean = this == ACTIVE
}
