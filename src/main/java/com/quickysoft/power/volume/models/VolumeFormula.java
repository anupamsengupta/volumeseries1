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
public record VolumeFormula(
        UUID id,
        UUID seriesId,
        BigDecimal baseVolume,                      // MW (for flat profiles)
        BigDecimal minVolume,                       // MW, floor for tolerance band
        BigDecimal maxVolume,                       // MW, cap for tolerance band
        List<ShapingEntry> shapingEntries,           // null if baseload
        String forecastSourceId,                     // external system ref
        BigDecimal forecastMultiplier,               // e.g., 0.9 = 90% of forecast
        List<SeasonalAdjustment> seasonalAdjustments,
        String calendarId                            // ref to holiday/trading calendar
) {}
