package com.mieai.qqbot.plugin.api

/** Bounded local media bytes supplied by a plugin to the host staging store. */
class MediaUpload(
    val kind: MediaKind,
    val fileName: String,
    val contentType: String,
    data: ByteArray,
) {
    private val dataBytes: ByteArray = data.clone()
    val data: ByteArray
        get() = dataBytes.clone()

    init {
        require(fileName.isNotBlank() && fileName.length <= 255) { "fileName is invalid" }
        require(contentType.isNotBlank() && contentType.length <= 128) { "contentType is invalid" }
        require(dataBytes.isNotEmpty()) { "data must not be empty" }
        require(dataBytes.size <= 256L * 1024L * 1024L) { "data must not exceed 256 MiB" }
    }

    override fun equals(other: Any?): Boolean =
        this === other || (other is MediaUpload && kind == other.kind &&
            fileName == other.fileName && contentType == other.contentType &&
            dataBytes.contentEquals(other.dataBytes))

    override fun hashCode(): Int {
        var result = kind.hashCode()
        result = 31 * result + fileName.hashCode()
        result = 31 * result + contentType.hashCode()
        result = 31 * result + dataBytes.contentHashCode()
        return result
    }

    override fun toString(): String =
        "MediaUpload(kind=$kind, fileName=$fileName, contentType=$contentType, data=<${dataBytes.size} bytes>)"
}
