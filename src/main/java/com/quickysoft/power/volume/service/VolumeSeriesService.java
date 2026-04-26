package com.quickysoft.power.volume.service;

import com.quickysoft.power.volume.models.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
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
        if (granularity == TimeGranularity.DAILY) {
            return (int) java.time.temporal.ChronoUnit.DAYS.between(
                    start.toLocalDate(), end.toLocalDate());
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
                Instant.now(), Instant.now(), intervals, null, null);
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
                Instant.now(), Instant.now(), intervals, null, null);
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
                merged, series.formula(), series.cascadeTier());
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
        if (granularity == TimeGranularity.DAILY) {
            return materializeDailyIntervals(start, end, volume, volumeUnit);
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

    private List<VolumeInterval> materializeDailyIntervals(
            ZonedDateTime start,
            ZonedDateTime end,
            BigDecimal volume,
            VolumeUnit volumeUnit) {

        List<VolumeInterval> intervals = new ArrayList<>();
        ZonedDateTime cursor = start;

        while (cursor.isBefore(end)) {
            ZonedDateTime intervalEnd = cursor.plusDays(1);
            if (intervalEnd.isAfter(end)) {
                intervalEnd = end;
            }

            // Each daily interval has variable duration due to DST — compute energy individually
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

    // ── Cascade methods ──────────────────────────────────────────────────

    /**
     * Build a three-tier cascade for a long-term PPA.
     * <ul>
     *   <li>NEAR_TERM: deliveryStart → weekEnd at baseGranularity, materialized upfront (FULL)</li>
     *   <li>MEDIUM_TERM: weekEnd → end of M+2 at DAILY, PENDING (no intervals yet)</li>
     *   <li>LONG_TERM: start of M+3 → deliveryEnd at MONTHLY, PENDING (no intervals yet)</li>
     * </ul>
     *
     * @param weekEnd end of current week (exclusive), e.g. next Monday 00:00
     */
    public CascadeResult buildCascadeSeries(
            String tradeId,
            String tradeLegId,
            ZonedDateTime deliveryStart,
            ZonedDateTime deliveryEnd,
            TimeGranularity baseGranularity,
            BigDecimal volume,
            ProfileType profileType,
            VolumeUnit volumeUnit,
            ZoneId zoneId,
            LocalDate weekEnd) {

        Instant now = Instant.now();
        ZonedDateTime weekEndZdt = weekEnd.atStartOfDay(zoneId);

        // Near-term: deliveryStart → weekEnd at base granularity, fully materialized
        ZonedDateTime nearEnd = weekEndZdt.isAfter(deliveryEnd) ? deliveryEnd : weekEndZdt;
        List<VolumeInterval> nearIntervals = materializeIntervals(
                deliveryStart, nearEnd, baseGranularity, volume, volumeUnit);
        int nearExpected = nearIntervals.size();
        VolumeSeries nearTerm = new VolumeSeries(
                UUID.randomUUID(), tradeId, tradeLegId, 1,
                volumeUnit, deliveryStart, nearEnd, zoneId, baseGranularity, profileType,
                MaterializationStatus.FULL, null, nearExpected, nearExpected,
                now, now, nearIntervals, null, CascadeTier.NEAR_TERM);

        // Medium-term: weekEnd → end of M+2 at DAILY, pending
        YearMonth currentMonth = YearMonth.from(deliveryStart);
        ZonedDateTime mediumEnd = currentMonth.plusMonths(3)
                .atDay(1).atStartOfDay(zoneId);
        if (mediumEnd.isAfter(deliveryEnd)) {
            mediumEnd = deliveryEnd;
        }
        ZonedDateTime mediumStart = weekEndZdt;
        int mediumExpected = calculateExpectedIntervals(mediumStart, mediumEnd, TimeGranularity.DAILY);
        VolumeSeries mediumTerm = new VolumeSeries(
                UUID.randomUUID(), tradeId, tradeLegId, 1,
                volumeUnit, mediumStart, mediumEnd, zoneId, TimeGranularity.DAILY, profileType,
                MaterializationStatus.PENDING, null, mediumExpected, 0,
                now, now, new ArrayList<>(), null, CascadeTier.MEDIUM_TERM);

        // Long-term: start of M+3 → deliveryEnd at MONTHLY, pending
        ZonedDateTime longStart = mediumEnd;
        int longExpected = calculateExpectedIntervals(longStart, deliveryEnd, TimeGranularity.MONTHLY);
        VolumeSeries longTerm = new VolumeSeries(
                UUID.randomUUID(), tradeId, tradeLegId, 1,
                volumeUnit, longStart, deliveryEnd, zoneId, TimeGranularity.MONTHLY, profileType,
                MaterializationStatus.PENDING, null, longExpected, 0,
                now, now, new ArrayList<>(), null, CascadeTier.LONG_TERM);

        return new CascadeResult(nearTerm, mediumTerm, longTerm);
    }

    /**
     * Materialize a chunk of daily intervals for a medium-term series.
     * Appends to existing intervals and promotes to FULL when complete.
     */
    public VolumeSeries materializeMediumTermChunk(
            VolumeSeries series,
            YearMonth chunkMonth,
            BigDecimal volume) {

        ZoneId zone = series.deliveryTimezone();
        ZonedDateTime chunkStart = chunkMonth.atDay(1).atStartOfDay(zone);
        ZonedDateTime chunkEnd = chunkMonth.plusMonths(1).atDay(1).atStartOfDay(zone);

        // Clamp to series window
        if (chunkStart.isBefore(series.deliveryStart())) {
            chunkStart = series.deliveryStart();
        }
        if (chunkEnd.isAfter(series.deliveryEnd())) {
            chunkEnd = series.deliveryEnd();
        }

        List<VolumeInterval> chunkIntervals = materializeDailyIntervals(
                chunkStart, chunkEnd, volume, series.volumeUnit());

        List<VolumeInterval> merged = new ArrayList<>(
                series.intervals().size() + chunkIntervals.size());
        merged.addAll(series.intervals());
        merged.addAll(chunkIntervals);

        int newCount = merged.size();
        YearMonth newThrough = series.materializedThrough() != null
                && series.materializedThrough().isAfter(chunkMonth)
                ? series.materializedThrough() : chunkMonth;

        boolean complete = newCount >= series.totalExpectedIntervals();
        MaterializationStatus newStatus = complete
                ? MaterializationStatus.FULL
                : MaterializationStatus.PARTIAL;
        YearMonth throughValue = complete ? null : newThrough;

        return new VolumeSeries(
                series.id(), series.tradeId(), series.tradeLegId(),
                series.tradeVersion(), series.volumeUnit(),
                series.deliveryStart(), series.deliveryEnd(), series.deliveryTimezone(),
                series.granularity(), series.profileType(),
                newStatus, throughValue,
                series.totalExpectedIntervals(), newCount,
                series.transactionTime(), series.validTime(),
                merged, series.formula(), series.cascadeTier());
    }

    /**
     * Materialize a single monthly interval for a long-term series.
     * Appends to existing intervals and promotes to FULL when complete.
     */
    public VolumeSeries materializeLongTermChunk(
            VolumeSeries series,
            YearMonth chunkMonth,
            BigDecimal volume) {

        ZoneId zone = series.deliveryTimezone();
        ZonedDateTime chunkStart = chunkMonth.atDay(1).atStartOfDay(zone);
        ZonedDateTime chunkEnd = chunkMonth.plusMonths(1).atDay(1).atStartOfDay(zone);

        // Clamp to series window
        if (chunkStart.isBefore(series.deliveryStart())) {
            chunkStart = series.deliveryStart();
        }
        if (chunkEnd.isAfter(series.deliveryEnd())) {
            chunkEnd = series.deliveryEnd();
        }

        List<VolumeInterval> chunkIntervals = materializeMonthlyIntervals(
                chunkStart, chunkEnd, volume, series.volumeUnit());

        List<VolumeInterval> merged = new ArrayList<>(
                series.intervals().size() + chunkIntervals.size());
        merged.addAll(series.intervals());
        merged.addAll(chunkIntervals);

        int newCount = merged.size();
        YearMonth newThrough = series.materializedThrough() != null
                && series.materializedThrough().isAfter(chunkMonth)
                ? series.materializedThrough() : chunkMonth;

        boolean complete = newCount >= series.totalExpectedIntervals();
        MaterializationStatus newStatus = complete
                ? MaterializationStatus.FULL
                : MaterializationStatus.PARTIAL;
        YearMonth throughValue = complete ? null : newThrough;

        return new VolumeSeries(
                series.id(), series.tradeId(), series.tradeLegId(),
                series.tradeVersion(), series.volumeUnit(),
                series.deliveryStart(), series.deliveryEnd(), series.deliveryTimezone(),
                series.granularity(), series.profileType(),
                newStatus, throughValue,
                series.totalExpectedIntervals(), newCount,
                series.transactionTime(), series.validTime(),
                merged, series.formula(), series.cascadeTier());
    }

    /**
     * Disaggregate a monthly interval from the long-term series into daily intervals
     * appended to the medium-term series.
     *
     * @return source (long-term with monthly removed), target (medium-term with daily appended)
     */
    public DisaggregationResult disaggregateMonthlyToDaily(
            VolumeSeries longTermSeries,
            VolumeSeries mediumTermSeries,
            YearMonth month,
            BigDecimal volume) {

        ZoneId zone = longTermSeries.deliveryTimezone();
        ZonedDateTime monthStart = month.atDay(1).atStartOfDay(zone);
        ZonedDateTime monthEnd = month.plusMonths(1).atDay(1).atStartOfDay(zone);

        // Remove the monthly interval for this month from long-term
        List<VolumeInterval> remainingLong = new ArrayList<>();
        for (VolumeInterval vi : longTermSeries.intervals()) {
            if (!vi.chunkMonth().equals(month)) {
                remainingLong.add(vi);
            }
        }

        int longNewCount = remainingLong.size();
        int longNewExpected = longTermSeries.totalExpectedIntervals() - 1;
        boolean longComplete = longNewCount >= longNewExpected;

        VolumeSeries updatedLong = new VolumeSeries(
                longTermSeries.id(), longTermSeries.tradeId(), longTermSeries.tradeLegId(),
                longTermSeries.tradeVersion(), longTermSeries.volumeUnit(),
                longTermSeries.deliveryStart(), longTermSeries.deliveryEnd(),
                longTermSeries.deliveryTimezone(),
                longTermSeries.granularity(), longTermSeries.profileType(),
                longComplete ? MaterializationStatus.FULL : longTermSeries.materializationStatus(),
                longTermSeries.materializedThrough(),
                longNewExpected, longNewCount,
                longTermSeries.transactionTime(), longTermSeries.validTime(),
                remainingLong, longTermSeries.formula(), longTermSeries.cascadeTier());

        // Generate daily intervals for the month and append to medium-term
        // Clamp to medium-term window
        ZonedDateTime dailyStart = monthStart.isBefore(mediumTermSeries.deliveryStart())
                ? mediumTermSeries.deliveryStart() : monthStart;
        ZonedDateTime dailyEnd = monthEnd.isAfter(mediumTermSeries.deliveryEnd())
                ? mediumTermSeries.deliveryEnd() : monthEnd;

        List<VolumeInterval> dailyIntervals = materializeDailyIntervals(
                dailyStart, dailyEnd, volume, mediumTermSeries.volumeUnit());

        List<VolumeInterval> mergedMedium = new ArrayList<>(
                mediumTermSeries.intervals().size() + dailyIntervals.size());
        mergedMedium.addAll(mediumTermSeries.intervals());
        mergedMedium.addAll(dailyIntervals);

        int medNewCount = mergedMedium.size();
        int medNewExpected = mediumTermSeries.totalExpectedIntervals() + dailyIntervals.size();
        boolean medComplete = medNewCount >= medNewExpected;

        VolumeSeries updatedMedium = new VolumeSeries(
                mediumTermSeries.id(), mediumTermSeries.tradeId(), mediumTermSeries.tradeLegId(),
                mediumTermSeries.tradeVersion(), mediumTermSeries.volumeUnit(),
                mediumTermSeries.deliveryStart(), mediumTermSeries.deliveryEnd(),
                mediumTermSeries.deliveryTimezone(),
                mediumTermSeries.granularity(), mediumTermSeries.profileType(),
                medComplete ? MaterializationStatus.FULL : MaterializationStatus.PARTIAL,
                mediumTermSeries.materializedThrough(),
                medNewExpected, medNewCount,
                mediumTermSeries.transactionTime(), mediumTermSeries.validTime(),
                mergedMedium, mediumTermSeries.formula(), mediumTermSeries.cascadeTier());

        return new DisaggregationResult(updatedLong, updatedMedium);
    }

    /**
     * Disaggregate daily intervals from the medium-term series into base-granularity
     * intervals appended to the near-term series.
     *
     * @param fromDate start date (inclusive)
     * @param toDate   end date (exclusive)
     * @return source (medium-term with daily removed), target (near-term with base intervals appended)
     */
    public DisaggregationResult disaggregateDailyToBase(
            VolumeSeries mediumTermSeries,
            VolumeSeries nearTermSeries,
            LocalDate fromDate,
            LocalDate toDate,
            BigDecimal volume) {

        ZoneId zone = mediumTermSeries.deliveryTimezone();
        ZonedDateTime rangeStart = fromDate.atStartOfDay(zone);
        ZonedDateTime rangeEnd = toDate.atStartOfDay(zone);

        // Remove daily intervals in [fromDate, toDate) from medium-term
        List<VolumeInterval> remainingMedium = new ArrayList<>();
        int removedCount = 0;
        for (VolumeInterval vi : mediumTermSeries.intervals()) {
            ZonedDateTime viStart = vi.intervalStart();
            if (!viStart.isBefore(rangeStart) && viStart.isBefore(rangeEnd)) {
                removedCount++;
            } else {
                remainingMedium.add(vi);
            }
        }

        int medNewExpected = mediumTermSeries.totalExpectedIntervals() - removedCount;
        int medNewCount = remainingMedium.size();

        VolumeSeries updatedMedium = new VolumeSeries(
                mediumTermSeries.id(), mediumTermSeries.tradeId(), mediumTermSeries.tradeLegId(),
                mediumTermSeries.tradeVersion(), mediumTermSeries.volumeUnit(),
                mediumTermSeries.deliveryStart(), mediumTermSeries.deliveryEnd(),
                mediumTermSeries.deliveryTimezone(),
                mediumTermSeries.granularity(), mediumTermSeries.profileType(),
                medNewCount >= medNewExpected ? MaterializationStatus.FULL
                        : mediumTermSeries.materializationStatus(),
                mediumTermSeries.materializedThrough(),
                medNewExpected, medNewCount,
                mediumTermSeries.transactionTime(), mediumTermSeries.validTime(),
                remainingMedium, mediumTermSeries.formula(), mediumTermSeries.cascadeTier());

        // Generate base-granularity intervals for the date range
        TimeGranularity baseGran = nearTermSeries.granularity();
        List<VolumeInterval> baseIntervals = materializeIntervals(
                rangeStart, rangeEnd, baseGran, volume, nearTermSeries.volumeUnit());

        List<VolumeInterval> mergedNear = new ArrayList<>(
                nearTermSeries.intervals().size() + baseIntervals.size());
        mergedNear.addAll(nearTermSeries.intervals());
        mergedNear.addAll(baseIntervals);

        int nearNewExpected = nearTermSeries.totalExpectedIntervals() + baseIntervals.size();
        int nearNewCount = mergedNear.size();

        VolumeSeries updatedNear = new VolumeSeries(
                nearTermSeries.id(), nearTermSeries.tradeId(), nearTermSeries.tradeLegId(),
                nearTermSeries.tradeVersion(), nearTermSeries.volumeUnit(),
                nearTermSeries.deliveryStart(), nearTermSeries.deliveryEnd(),
                nearTermSeries.deliveryTimezone(),
                nearTermSeries.granularity(), nearTermSeries.profileType(),
                MaterializationStatus.FULL,
                nearTermSeries.materializedThrough(),
                nearNewExpected, nearNewCount,
                nearTermSeries.transactionTime(), nearTermSeries.validTime(),
                mergedNear, nearTermSeries.formula(), nearTermSeries.cascadeTier());

        return new DisaggregationResult(updatedMedium, updatedNear);
    }

    public BigDecimal totalEnergy(VolumeSeries series) {
        BigDecimal result = series.intervals().stream()
                .map(VolumeInterval::energy)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        result = result.setScale(6, RoundingMode.HALF_UP);
        return result;
    }

}
