package com.mieai.qqbot.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Minimal response fields; QQ may add fields without breaking the sender. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record QqMessageSendResult(
        String id,
        @com.fasterxml.jackson.annotation.JsonProperty("msg_seq") Integer msgSeq,
        String timestamp) {}
