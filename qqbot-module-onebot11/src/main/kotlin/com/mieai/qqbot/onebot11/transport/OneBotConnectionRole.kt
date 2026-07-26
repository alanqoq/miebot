package com.mieai.qqbot.onebot11.transport

enum class OneBotConnectionRole {
    API,
    EVENT,
    UNIVERSAL;

    fun acceptsActions(): Boolean = this == API || this == UNIVERSAL

    fun acceptsEvents(): Boolean = this == EVENT || this == UNIVERSAL
}
