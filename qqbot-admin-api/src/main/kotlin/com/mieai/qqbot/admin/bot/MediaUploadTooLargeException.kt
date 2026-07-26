package com.mieai.qqbot.admin.bot

class MediaUploadTooLargeException(
    val actualBytes: Long,
    val maxBytes: Long,
) : RuntimeException("媒体文件超过该机器人的上传上限")
