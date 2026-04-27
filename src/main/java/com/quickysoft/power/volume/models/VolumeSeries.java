package com.quickysoft.power.volume.models;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import com.quickysoft.power.volume.models.enums.*;
import com.quickysoft.power.volume.service.VolumeSeriesService;

import java.util.List;
import java.util.UUID;

/**
 * Root aggregate for a trade's delivery volume series.
 * <p>
 * One trade version has exactly one VolumeSeries. This is the "meal" —
 * the materialized intervals — along with a reference to the "recipe"
 * ({@link VolumeFormula}) that can regenerate them.
 * <p>
 * Supports the two-tier materialization strategy:
 * <ul>
 *   <li>Short-term (DA/Intraday): all intervals generated immediately, status=FULL</li>
 *   <li>Long-term (PPA): rolling horizon M+1 to M+3, status=PARTIAL,
 *       with monthly cron extending the window</li>
 * </ul>
 * <p>
 * Immutable once created. Amendments create a new trade version
 * with a new VolumeSeries, not in-place edits. The intervals list
 * can be mutated by manipulating its contents directly.
 */
public record VolumeSeries(
        UUID id,
        String tradeId,
        String tradeLegId,
        int tradeVersion,
        VolumeUnit volumeUnit,
        ZonedDateTime deliveryStart,
        ZonedDateTime deliveryEnd,
        ZoneId deliveryTimezone,
        TimeGranularity granularity,
        ProfileType profileType,
        MaterializationStatus materializationStatus,
        YearMonth materializedThrough,
        int totalExpectedIntervals,
        int materializedIntervalCount,
        Instant transactionTime,
        Instant validTime,
        List<VolumeInterval> intervals,
        VolumeFormula formula,
        CascadeTier cascadeTier
) {

    /**
     * Calculate expected interval count for the full delivery period.
     * <p>
     * Walks the timeline using ZonedDateTime arithmetic, which correctly
     * handles DST transitions (23-hour and 25-hour days).
     */
    public int calculateExpectedIntervals() {
        return VolumeSeriesService.calculateExpectedIntervals(
                deliveryStart, deliveryEnd, granularity);
    }

    /**
     * Get the unmaterialized delivery window (for contract-level position).
     *
     * @return the unmaterialized window, or null if fully materialized
     */
    public DeliveryWindow getUnmaterializedWindow() {
        if (materializationStatus == MaterializationStatus.FULL) {
            return null;
        }
        if (materializedThrough == null) {
            return new DeliveryWindow(deliveryStart, deliveryEnd);
        }
        ZonedDateTime unmaterializedStart = materializedThrough
                .plusMonths(1)
                .atDay(1)
                .atStartOfDay(deliveryTimezone);
        if (!unmaterializedStart.isBefore(deliveryEnd)) {
            return null;
        }
        return new DeliveryWindow(unmaterializedStart, deliveryEnd);
    }

    /**
     * Returns a new VolumeSeries with the given formula attached.
     */
    public VolumeSeries withFormula(VolumeFormula formula) {
        return new VolumeSeries(id, tradeId, tradeLegId, tradeVersion, volumeUnit,
                deliveryStart, deliveryEnd, deliveryTimezone, granularity, profileType,
                materializationStatus, materializedThrough, totalExpectedIntervals,
                materializedIntervalCount, transactionTime, validTime, intervals, formula,
                cascadeTier);
    }
}
