package com.quickysoft.power.volume.service;

import com.quickysoft.power.volume.models.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class VolumeSeriesService {

    private static final VolumeSeriesService VOLUME_SERIES_SERVICE = new VolumeSeriesService();

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
        series.setMaterializedIntervalCount(intervals.size());
        series.setTotalExpectedIntervals(series.calculateExpectedIntervals());

        return series;
    }

    public List<VolumeInterval> materializeIntervals(
            ZonedDateTime start,
            ZonedDateTime end,
            TimeGranularity granularity,
            BigDecimal volume,
            VolumeUnit volumeUnit) {

        List<VolumeInterval> intervals = new ArrayList<>();
        ZonedDateTime cursor = start;

        while (cursor.isBefore(end)) {
            ZonedDateTime intervalEnd;
            if (granularity == TimeGranularity.MONTHLY) {
                intervalEnd = cursor.plusMonths(1);
                if (intervalEnd.isAfter(end)) {
                    intervalEnd = end;
                }
            } else {
                intervalEnd = cursor.plus(granularity.getFixedDuration());
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
