package com.mieai.qqbot.protocol.error;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Error payload returned by QQ OpenAPI endpoints. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record QqApiError(
        int code,
        String message,
        @JsonProperty("trace_id") String traceId) {}
