package com.mieai.qqbot.protocol.gateway;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Framework-neutral representation of a Gateway payload.
 *
 * <p>The raw opcode is retained so newer opcodes remain diagnosable even before this library knows
 * about them.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GatewayEnvelope<T>(
        @JsonProperty("op") int op,
        @JsonProperty("d") T data,
        @JsonProperty("s") Long sequence,
        @JsonProperty("t") String eventType,
        @JsonProperty("id") String eventId) {

    /**
     * Source-compatible constructor for frames that do not carry an event id (for example
     * client-to-server heartbeat and identify frames).
     */
    public GatewayEnvelope(int op, T data, Long sequence, String eventType) {
        this(op, data, sequence, eventType, null);
    }

    public GatewayOpcode opcode() {
        return GatewayOpcode.fromCode(op);
    }
}
