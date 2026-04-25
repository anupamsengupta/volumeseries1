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
 * Immutable once confirmed. Amendments create a new trade version
 * with a new VolumeSeries, not in-place edits.
 * <p>
 * Energy is derived from volume × actual elapsed duration (not nominal),
 * which correctly handles DST transition days (23h and 25h days).
 */
public class VolumeInterval {

    private UUID id;
    private UUID seriesId;

    // ── Time boundaries (in delivery timezone) ──
    private ZonedDateTime intervalStart;
    private ZonedDateTime intervalEnd;

    // ── Volume ──
    private BigDecimal volume;    // MW (power capacity)
    private BigDecimal energy;    // MWh (derived: volume × duration in hours)

    // ── Status ──
    private IntervalStatus status;

    // ── Chunking metadata (for PPA monthly chunk tracking) ──
    private YearMonth chunkMonth;

    /**
     * Derive energy from volume and actual interval duration.
     * <p>
     * Uses Instant-based duration to handle DST correctly.
     * On the October DST fall-back day, an interval spanning the
     * transition will correctly reflect the actual elapsed seconds.
     *
     * Behaviour depends on the volumeUnit set on the parent series:
     *
     * MW_CAPACITY:     energy = volume (MW) × elapsed hours
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
        return volume.multiply(hours);
    }

    // ── Getters and Setters ──

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getSeriesId() {
        return seriesId;
    }

    public void setSeriesId(UUID seriesId) {
        this.seriesId = seriesId;
    }

    public ZonedDateTime getIntervalStart() {
        return intervalStart;
    }

    public void setIntervalStart(ZonedDateTime intervalStart) {
        this.intervalStart = intervalStart;
    }

    public ZonedDateTime getIntervalEnd() {
        return intervalEnd;
    }

    public void setIntervalEnd(ZonedDateTime intervalEnd) {
        this.intervalEnd = intervalEnd;
    }

    public BigDecimal getVolume() {
        return volume;
    }

    public void setVolume(BigDecimal volume) {
        this.volume = volume;
    }

    public BigDecimal getEnergy() {
        return energy;
    }

    public void setEnergy(BigDecimal energy) {
        this.energy = energy;
    }

    public IntervalStatus getStatus() {
        return status;
    }

    public void setStatus(IntervalStatus status) {
        this.status = status;
    }

    public YearMonth getChunkMonth() {
        return chunkMonth;
    }

    public void setChunkMonth(YearMonth chunkMonth) {
        this.chunkMonth = chunkMonth;
    }
}
