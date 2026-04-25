package com.quickysoft.power.volume.models;

import java.time.Duration;

/**
 * Supported delivery interval granularities for EU physical power markets.
 * <p>
 * Sub-daily granularities (5-min through hourly) have fixed durations.
 * DAILY and MONTHLY are variable due to DST transitions and month lengths.
 */
public enum TimeGranularity {

    MIN_5(Duration.ofMinutes(5)),
    MIN_15(Duration.ofMinutes(15)),
    MIN_30(Duration.ofMinutes(30)),
    HOURLY(Duration.ofHours(1)),
    DAILY(Duration.ofDays(1)),
    MONTHLY(null);

    private final Duration fixedDuration;

    TimeGranularity(Duration fixedDuration) {
        this.fixedDuration = fixedDuration;
    }

    /**
     * Returns the fixed duration for sub-daily and daily granularities.
     *
     * @throws UnsupportedOperationException for MONTHLY (variable length)
     */
    public Duration getFixedDuration() {
        if (this == MONTHLY) {
            throw new UnsupportedOperationException(
                    "MONTHLY has variable duration; use YearMonth arithmetic");
        }
        return fixedDuration;
    }

    public boolean isSubDaily() {
        return this.ordinal() < DAILY.ordinal();
    }
}
