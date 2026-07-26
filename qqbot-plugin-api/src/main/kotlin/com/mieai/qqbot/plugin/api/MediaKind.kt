package com.mieai.qqbot.plugin.api

enum class MediaKind(
    val qqFileType: Int,
) {
    IMAGE(1),
    VIDEO(2),
    AUDIO(3),
    FILE(4),
    ;
}
