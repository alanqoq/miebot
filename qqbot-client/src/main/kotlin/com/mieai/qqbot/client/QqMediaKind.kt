package com.mieai.qqbot.client

enum class QqMediaKind(private val wireFileType: Int) {
    IMAGE(1),
    VIDEO(2),
    AUDIO(3),
    FILE(4),
    ;

    fun fileType(): Int = wireFileType
}
