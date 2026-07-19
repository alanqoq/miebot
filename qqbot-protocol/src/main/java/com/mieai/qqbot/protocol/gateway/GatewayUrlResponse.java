package com.mieai.qqbot.protocol.gateway;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Gateway discovery response. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GatewayUrlResponse(String url) {}
