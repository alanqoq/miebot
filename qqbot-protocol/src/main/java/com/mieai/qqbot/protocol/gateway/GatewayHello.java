package com.mieai.qqbot.protocol.gateway;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Data carried by a Gateway Hello envelope. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GatewayHello(@JsonProperty("heartbeat_interval") long heartbeatInterval) {}
