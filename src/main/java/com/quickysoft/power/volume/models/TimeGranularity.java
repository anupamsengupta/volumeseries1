package com.quickysoft.power.volume.models;

import java.time.Duration;

/**
 * Supported delivery interval granularities for EU physical power markets.
 * <p>
 * Sub-daily granularities (5-min through hourly) have fixed durations.
 * DAILY and MONTHLY are variable due to DST transitions and month lengths
 * and must use ZonedDateTime arithmetic instead of Instant arithmetic.
 */
public enum TimeGranularity {

    MIN_5(Duration.ofMinutes(5)),
    MIN_15(Duration.ofMinutes(15)),
    MIN_30(Duration.ofMinutes(30)),
    HOURLY(Duration.ofHours(1)),
    DAILY(null),
    MONTHLY(null);

    private final Duration fixedDuration;

    TimeGranularity(Duration fixedDuration) {
        this.fixedDuration = fixedDuration;
    }

    /**
     * Returns the fixed duration for sub-daily granularities.
     *
     * @throws UnsupportedOperationException for DAILY and MONTHLY (variable length)
     */
    public Duration getFixedDuration() {
        if (fixedDuration == null) {
            throw new UnsupportedOperationException(
                    this + " has variable duration; use ZonedDateTime arithmetic");
        }
        return fixedDuration;
    }

    /**
     * Whether this granularity has a fixed duration independent of DST or month length.
     */
    public boolean isFixedDuration() {
        return fixedDuration != null;
    }

    public boolean isSubDaily() {
        return this.ordinal() < DAILY.ordinal();
    }
}
