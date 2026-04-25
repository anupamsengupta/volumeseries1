package com.quickysoft.power.volume.models;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Contract-level volume definition — the "recipe" for generating intervals.
 * <p>
 * This is the source of truth for a trade's delivery obligation. The
 * materialized {@link VolumeInterval} rows are derived from this formula.
 * <p>
 * For a baseload PPA: profileType=BASELOAD, baseVolume=50MW, done.
 * For a shaped PPA: profileType=SHAPED, shapingEntries define volume
 * per time-of-use block.
 * For generation-following: profileType=GENERATION_FOLLOWING,
 * forecastSourceId links to the renewable generation forecast feed.
 * <p>
 * Immutable per trade version.
 */
public class VolumeFormula {

    private UUID id;
    private UUID seriesId;

    // ── Base volume (for flat profiles) ──
    private BigDecimal baseVolume;          // MW
    private BigDecimal minVolume;           // MW, floor for tolerance band
    private BigDecimal maxVolume;           // MW, cap for tolerance band

    // ── Shaping (for profiled/shaped deliveries) ──
    private List<ShapingEntry> shapingEntries;   // null if baseload

    // ── Generation-following ──
    private String forecastSourceId;        // external system ref
    private BigDecimal forecastMultiplier;  // e.g., 0.9 = 90% of forecast

    // ── Seasonal/annual adjustments ──
    private List<SeasonalAdjustment> seasonalAdjustments;

    // ── Calendar reference (for peak/offpeak determination) ──
    private String calendarId;             // ref to holiday/trading calendar

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

    public BigDecimal getBaseVolume() {
        return baseVolume;
    }

    public void setBaseVolume(BigDecimal baseVolume) {
        this.baseVolume = baseVolume;
    }

    public BigDecimal getMinVolume() {
        return minVolume;
    }

    public void setMinVolume(BigDecimal minVolume) {
        this.minVolume = minVolume;
    }

    public BigDecimal getMaxVolume() {
        return maxVolume;
    }

    public void setMaxVolume(BigDecimal maxVolume) {
        this.maxVolume = maxVolume;
    }

    public List<ShapingEntry> getShapingEntries() {
        return shapingEntries;
    }

    public void setShapingEntries(List<ShapingEntry> shapingEntries) {
        this.shapingEntries = shapingEntries;
    }

    public String getForecastSourceId() {
        return forecastSourceId;
    }

    public void setForecastSourceId(String forecastSourceId) {
        this.forecastSourceId = forecastSourceId;
    }

    public BigDecimal getForecastMultiplier() {
        return forecastMultiplier;
    }

    public void setForecastMultiplier(BigDecimal forecastMultiplier) {
        this.forecastMultiplier = forecastMultiplier;
    }

    public List<SeasonalAdjustment> getSeasonalAdjustments() {
        return seasonalAdjustments;
    }

    public void setSeasonalAdjustments(List<SeasonalAdjustment> seasonalAdjustments) {
        this.seasonalAdjustments = seasonalAdjustments;
    }

    public String getCalendarId() {
        return calendarId;
    }

    public void setCalendarId(String calendarId) {
        this.calendarId = calendarId;
    }
}
