package com.mieai.qqbot.protocol.error

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/** Error payload returned by QQ OpenAPI endpoints. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class QqApiError(
    @JsonAlias("err_code") val code: Int,
    val message: String?,
    @JsonProperty("trace_id") val traceId: String?,
)
