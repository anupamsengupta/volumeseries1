package com.quickysoft.power.volume.service;

import com.quickysoft.power.volume.models.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class VolumeSeriesService {

    private static final VolumeSeriesService VOLUME_SERIES_SERVICE = new VolumeSeriesService();
    private static final BigDecimal SECONDS_PER_HOUR = BigDecimal.valueOf(3600);

    private VolumeSeriesService() {}

    public static VolumeSeriesService getInstance() {
        return VOLUME_SERIES_SERVICE;
    }

    /**
     * Calculate expected interval count for a delivery window and granularity.
     * Uses ZonedDateTime arithmetic to correctly handle DST transitions.
     */
    public static int calculateExpectedIntervals(
            ZonedDateTime start, ZonedDateTime end, TimeGranularity granularity) {
        if (granularity == TimeGranularity.MONTHLY) {
            return (int) java.time.temporal.ChronoUnit.MONTHS.between(
                    YearMonth.from(start), YearMonth.from(end));
        }
        int count = 0;
        ZonedDateTime cursor = start;
        while (cursor.isBefore(end)) {
            count++;
            cursor = cursor.plus(granularity.getFixedDuration());
        }
        return count;
    }

    public VolumeSeries buildSeries(
            String tradeId,
            String tradeLegId,
            ZonedDateTime start,
            ZonedDateTime end,
            TimeGranularity granularity,
            BigDecimal volume,
            ProfileType profileType,
            MaterializationStatus matStatus,
            VolumeUnit volumeUnit,
            ZoneId zoneId) {

        List<VolumeInterval> intervals = materializeIntervals(
                start, end, granularity, volume, volumeUnit);
        int intervalCount = intervals.size();

        return new VolumeSeries(
                UUID.randomUUID(), tradeId, tradeLegId, 1,
                volumeUnit, start, end, zoneId, granularity, profileType,
                matStatus, null, intervalCount, intervalCount,
                Instant.now(), Instant.now(), intervals, null);
    }

    /**
     * Build a PARTIAL series: materializes only through the given month,
     * but records the full delivery window for downstream position aggregation.
     */
    public VolumeSeries buildPartialSeries(
            String tradeId,
            String tradeLegId,
            ZonedDateTime deliveryStart,
            ZonedDateTime deliveryEnd,
            TimeGranularity granularity,
            BigDecimal volume,
            ProfileType profileType,
            VolumeUnit volumeUnit,
            ZoneId zoneId,
            YearMonth materializedThrough) {

        ZonedDateTime partialEnd = materializedThrough.plusMonths(1)
                .atDay(1).atStartOfDay(zoneId);
        if (partialEnd.isAfter(deliveryEnd)) {
            partialEnd = deliveryEnd;
        }

        List<VolumeInterval> intervals = materializeIntervals(
                deliveryStart, partialEnd, granularity, volume, volumeUnit);

        int totalExpected = calculateExpectedIntervals(deliveryStart, deliveryEnd, granularity);

        return new VolumeSeries(
                UUID.randomUUID(), tradeId, tradeLegId, 1,
                volumeUnit, deliveryStart, deliveryEnd, zoneId, granularity, profileType,
                MaterializationStatus.PARTIAL, materializedThrough,
                totalExpected, intervals.size(),
                Instant.now(), Instant.now(), intervals, null);
    }

    /**
     * Materialize a single monthly chunk and append it to an existing PARTIAL series.
     * Returns a new VolumeSeries with the chunk intervals merged in order.
     * If all intervals are now materialized, status is promoted to FULL.
     */
    public VolumeSeries materializeChunk(
            VolumeSeries series,
            YearMonth chunkMonth,
            BigDecimal volume) {

        ZoneId zone = series.deliveryTimezone();
        ZonedDateTime chunkStart = chunkMonth.atDay(1).atStartOfDay(zone);
        ZonedDateTime chunkEnd = chunkMonth.plusMonths(1).atDay(1).atStartOfDay(zone);

        // Clamp to delivery window
        if (chunkStart.isBefore(series.deliveryStart())) {
            chunkStart = series.deliveryStart();
        }
        if (chunkEnd.isAfter(series.deliveryEnd())) {
            chunkEnd = series.deliveryEnd();
        }

        List<VolumeInterval> chunkIntervals = materializeIntervals(
                chunkStart, chunkEnd, series.granularity(), volume, series.volumeUnit());

        // Merge: existing intervals + new chunk (maintains chronological order
        // since chunks are processed sequentially after the materialized window)
        List<VolumeInterval> merged = new ArrayList<>(
                series.intervals().size() + chunkIntervals.size());
        merged.addAll(series.intervals());
        merged.addAll(chunkIntervals);

        int newCount = merged.size();
        YearMonth newMaterializedThrough = series.materializedThrough() != null
                && series.materializedThrough().isAfter(chunkMonth)
                ? series.materializedThrough() : chunkMonth;

        // Promote to FULL if all intervals are materialized
        boolean complete = newCount >= series.totalExpectedIntervals();
        MaterializationStatus newStatus = complete
                ? MaterializationStatus.FULL : MaterializationStatus.PARTIAL;
        YearMonth throughValue = complete ? null : newMaterializedThrough;

        return new VolumeSeries(
                series.id(), series.tradeId(), series.tradeLegId(),
                series.tradeVersion(), series.volumeUnit(),
                series.deliveryStart(), series.deliveryEnd(), series.deliveryTimezone(),
                series.granularity(), series.profileType(),
                newStatus, throughValue,
                series.totalExpectedIntervals(), newCount,
                series.transactionTime(), series.validTime(),
                merged, series.formula());
    }

    public List<VolumeInterval> materializeIntervals(
            ZonedDateTime start,
            ZonedDateTime end,
            TimeGranularity granularity,
            BigDecimal volume,
            VolumeUnit volumeUnit) {

        if (granularity == TimeGranularity.MONTHLY) {
            return materializeMonthlyIntervals(start, end, volume, volumeUnit);
        }

        Duration fixedDuration = granularity.getFixedDuration();
        long fixedSeconds = fixedDuration.getSeconds();
        Instant startInstant = start.toInstant();
        Instant endInstant = end.toInstant();
        ZoneId zone = start.getZone();

        // Pre-size: exact estimate for fixed-duration granularities
        int estimated = (int) (Duration.between(startInstant, endInstant).getSeconds() / fixedSeconds);
        List<VolumeInterval> intervals = new ArrayList<>(estimated);

        // Pre-compute energy: for fixed-duration intervals every interval has identical energy
        BigDecimal precomputedEnergy;
        if (volumeUnit == VolumeUnit.MWH_PER_PERIOD) {
            precomputedEnergy = volume;
        } else {
            BigDecimal hours = BigDecimal.valueOf(fixedSeconds)
                    .divide(SECONDS_PER_HOUR, 20, RoundingMode.HALF_UP);
            precomputedEnergy = volume.multiply(hours).setScale(6, RoundingMode.HALF_UP);
        }

        // Cached chunk month — only recompute on month boundary
        int cachedMonth = -1;
        int cachedYear = -1;
        YearMonth cachedChunkMonth = null;

        Instant cursor = startInstant;
        while (cursor.isBefore(endInstant)) {
            Instant intervalEndInstant = cursor.plusSeconds(fixedSeconds);

            ZonedDateTime intervalStart = cursor.atZone(zone);
            ZonedDateTime intervalEnd = intervalEndInstant.atZone(zone);

            // Update chunk month only when month/year changes
            int month = intervalStart.getMonthValue();
            int year = intervalStart.getYear();
            if (month != cachedMonth || year != cachedYear) {
                cachedChunkMonth = YearMonth.of(year, month);
                cachedMonth = month;
                cachedYear = year;
            }

            intervals.add(new VolumeInterval(
                    UUID.randomUUID(), null,
                    intervalStart, intervalEnd,
                    volume, precomputedEnergy,
                    IntervalStatus.CONFIRMED, cachedChunkMonth));

            cursor = intervalEndInstant;
        }
        return intervals;
    }

    private List<VolumeInterval> materializeMonthlyIntervals(
            ZonedDateTime start,
            ZonedDateTime end,
            BigDecimal volume,
            VolumeUnit volumeUnit) {

        List<VolumeInterval> intervals = new ArrayList<>();
        ZonedDateTime cursor = start;

        while (cursor.isBefore(end)) {
            ZonedDateTime intervalEnd = cursor.plusMonths(1);
            if (intervalEnd.isAfter(end)) {
                intervalEnd = end;
            }

            // For MONTHLY, each interval has variable duration — compute energy individually
            BigDecimal energy;
            if (volumeUnit == VolumeUnit.MWH_PER_PERIOD) {
                energy = volume;
            } else {
                long seconds = Duration.between(cursor.toInstant(), intervalEnd.toInstant()).getSeconds();
                BigDecimal hours = BigDecimal.valueOf(seconds)
                        .divide(SECONDS_PER_HOUR, 20, RoundingMode.HALF_UP);
                energy = volume.multiply(hours).setScale(6, RoundingMode.HALF_UP);
            }

            intervals.add(new VolumeInterval(
                    UUID.randomUUID(), null,
                    cursor, intervalEnd,
                    volume, energy,
                    IntervalStatus.CONFIRMED, YearMonth.from(cursor)));

            cursor = intervalEnd;
        }
        return intervals;
    }

    public BigDecimal totalEnergy(VolumeSeries series) {
        BigDecimal result = series.intervals().stream()
                .map(VolumeInterval::energy)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        result = result.setScale(6, RoundingMode.HALF_UP);
        return result;
    }

}
