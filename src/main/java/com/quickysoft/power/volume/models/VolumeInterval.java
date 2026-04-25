package com.quickysoft.power.volume.models;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.util.UUID;

/**
 * Single delivery interval within a materialized volume series.
 * <p>
 * Immutable once created. Amendments create a new trade version
 * with a new VolumeSeries, not in-place edits.
 * <p>
 * Energy is derived from volume x actual elapsed duration (not nominal),
 * which correctly handles DST transition days (23h and 25h days).
 */
public record VolumeInterval(
        UUID id,
        UUID seriesId,
        ZonedDateTime intervalStart,    // inclusive, in delivery timezone
        ZonedDateTime intervalEnd,      // exclusive, in delivery timezone
        BigDecimal volume,              // interpretation depends on VolumeUnit
        BigDecimal energy,              // MWh (derived)
        IntervalStatus status,
        YearMonth chunkMonth            // which materialization chunk produced this
) {
    /**
     * Derive energy from volume and actual interval duration.
     * <p>
     * Uses Instant-based duration to handle DST correctly.
     * On the October DST fall-back day, an interval spanning the
     * transition will correctly reflect the actual elapsed seconds.
     *
     * Behaviour depends on the volumeUnit set on the parent series:
     *
     * MW_CAPACITY:     energy = volume (MW) x elapsed hours
     * MWH_PER_PERIOD:  energy = volume (the value IS MWh already)
     * @return energy in MWh
     */
    public BigDecimal calculateEnergy(VolumeUnit volumeUnit) {
        if (volumeUnit == VolumeUnit.MWH_PER_PERIOD) {
            return volume;
        }

        // MW_CAPACITY: derive from actual elapsed time (DST-safe)
        long seconds = Duration.between(
                intervalStart.toInstant(),
                intervalEnd.toInstant()).getSeconds();
        BigDecimal hours = BigDecimal.valueOf(seconds)
                .divide(BigDecimal.valueOf(3600), 20, RoundingMode.HALF_UP);
        return volume.multiply(hours).setScale(6, RoundingMode.HALF_UP);
    }
}
