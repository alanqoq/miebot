package com.mieai.qqbot.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record QqMediaUploadResult(
        @JsonProperty("file_uuid") String fileUuid,
        @JsonProperty("file_info") String fileInfo,
        Integer ttl) {
}
