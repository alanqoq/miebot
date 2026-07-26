package com.mieai.qqbot.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class QqMediaUploadResult(
    @JsonProperty("file_uuid") val fileUuid: String?,
    @JsonProperty("file_info") val fileInfo: String?,
    val ttl: Int?,
)
