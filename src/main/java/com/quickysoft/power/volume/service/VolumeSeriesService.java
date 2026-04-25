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

    public VolumeSeries buildSeries(
            ZonedDateTime start,
            ZonedDateTime end,
            TimeGranularity granularity,
            BigDecimal volume,
            ProfileType profileType,
            MaterializationStatus matStatus,
            VolumeUnit volumeUnit,
            ZoneId zoneId) {

        VolumeSeries series = new VolumeSeries();
        series.setId(UUID.randomUUID());
        series.setTradeId(UUID.randomUUID());
        series.setTradeLegId(UUID.randomUUID());
        series.setTradeVersion(1);
        series.setDeliveryStart(start);
        series.setDeliveryEnd(end);
        series.setDeliveryTimezone(zoneId);
        series.setGranularity(granularity);
        series.setProfileType(profileType);
        series.setMaterializationStatus(matStatus);
        series.setTransactionTime(Instant.now());
        series.setValidTime(Instant.now());

        List<VolumeInterval> intervals = materializeIntervals(
                start, end, granularity, volume, volumeUnit);
        series.setIntervals(intervals);

        int intervalCount = intervals.size();
        series.setMaterializedIntervalCount(intervalCount);
        series.setTotalExpectedIntervals(intervalCount);

        return series;
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

            VolumeInterval interval = new VolumeInterval();
            interval.setId(UUID.randomUUID());
            interval.setIntervalStart(intervalStart);
            interval.setIntervalEnd(intervalEnd);
            interval.setVolume(volume);
            interval.setEnergy(precomputedEnergy);
            interval.setStatus(IntervalStatus.CONFIRMED);
            interval.setChunkMonth(cachedChunkMonth);

            intervals.add(interval);
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

            VolumeInterval interval = new VolumeInterval();
            interval.setId(UUID.randomUUID());
            interval.setIntervalStart(cursor);
            interval.setIntervalEnd(intervalEnd);
            interval.setVolume(volume);
            interval.setEnergy(interval.calculateEnergy(volumeUnit));
            interval.setStatus(IntervalStatus.CONFIRMED);
            interval.setChunkMonth(YearMonth.from(cursor));

            intervals.add(interval);
            cursor = intervalEnd;
        }
        return intervals;
    }

    public BigDecimal totalEnergy(VolumeSeries series) {
        BigDecimal result = series.getIntervals().stream()
                .map(VolumeInterval::getEnergy)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        result = result.setScale(6, RoundingMode.HALF_UP);
        return result;
    }

}
