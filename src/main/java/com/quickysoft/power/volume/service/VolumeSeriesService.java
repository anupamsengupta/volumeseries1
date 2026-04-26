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
