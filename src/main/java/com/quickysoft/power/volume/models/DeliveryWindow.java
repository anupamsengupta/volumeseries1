package com.quickysoft.power.volume.models;

import java.time.Duration;
import java.time.ZonedDateTime;

/**
 * Represents a contiguous delivery time window.
 * Used for both full delivery periods and unmaterialized portions.
 */
public record DeliveryWindow(
        ZonedDateTime start,
        ZonedDateTime end
) {
    public DeliveryWindow {
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException(
                    "Delivery window end must be after start: " + start + " to " + end);
        }
    }

    /**
     * Actual elapsed duration (handles DST correctly via Instant conversion).
     */
    public Duration duration() {
        return Duration.between(start.toInstant(), end.toInstant());
    }
}
