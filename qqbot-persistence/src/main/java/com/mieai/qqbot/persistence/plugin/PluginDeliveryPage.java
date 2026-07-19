package com.mieai.qqbot.persistence.plugin;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** One immutable page of plugin deliveries and an optional next-page cursor. */
public record PluginDeliveryPage(
        List<PluginDelivery> deliveries,
        Optional<String> nextCursor) {

    public PluginDeliveryPage {
        deliveries = List.copyOf(Objects.requireNonNull(deliveries, "deliveries must not be null"));
        nextCursor = Objects.requireNonNull(nextCursor, "nextCursor must not be null");
    }
}
