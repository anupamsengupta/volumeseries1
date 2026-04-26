package com.quickysoft.power.volume;

import com.quickysoft.power.volume.models.*;
import com.quickysoft.power.volume.service.VolumeSeriesService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Volume series tests for EU physical power trade capture.
 * <p>
 * All tests use Europe/Berlin (CET/CEST) as delivery timezone,
 * which is the standard for German bidding zone (DE-LU).
 * <p>
 * Convention:
 * <ul>
 *   <li>Volume is in MW (power capacity)</li>
 *   <li>Energy is in MWh (volume × duration in hours)</li>
 *   <li>15 MW delivered for 15 minutes = 15 × 0.25 = 3.75 MWh</li>
 *   <li>15 MW delivered for 30 minutes = 15 × 0.50 = 7.50 MWh</li>
 *   <li>15 MW delivered for 60 minutes = 15 × 1.00 = 15.00 MWh</li>
 * </ul>
 */
class VolumeSeriesTest {

    private static final ZoneId DELIVERY_TZ = ZoneId.of("Europe/Berlin");
    private static final BigDecimal VOLUME_MW = new BigDecimal("15");

    // ════════════════════════════════════════════════════════════════
    // Helper: build a VolumeSeries with materialized intervals
    // ════════════════════════════════════════════════════════════════
    VolumeSeriesService volumeSeriesService = VolumeSeriesService.getInstance();


    // ════════════════════════════════════════════════════════════════
    // TEST 1: Single 15-min interval (17:45 - 18:00)
    // ════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Scenario 1: Single 15-min interval, 15 MW, 17:45-18:00, 24 Apr 2026")
    class SingleQuarterHourInterval {

        private final ZonedDateTime start =
                ZonedDateTime.of(2026, 4, 24, 17, 45, 0, 0, DELIVERY_TZ);
        private final ZonedDateTime end =
                ZonedDateTime.of(2026, 4, 24, 18, 0, 0, 0, DELIVERY_TZ);

        @Test
        @DisplayName("Should produce exactly 1 interval")
        void shouldProduceOneInterval() {
            VolumeSeries series = volumeSeriesService.buildSeries(
                    UUID.randomUUID().toString(),
                    UUID.randomUUID().toString(),
                    start, end, TimeGranularity.MIN_15,
                    VOLUME_MW, ProfileType.BLOCK,
                    MaterializationStatus.FULL,
                    VolumeUnit.MWH_PER_PERIOD,
                    DELIVERY_TZ);

            assertEquals(1, series.intervals().size());
            assertEquals(1, series.totalExpectedIntervals());
            assertEquals(1, series.materializedIntervalCount());
        }

        @Test
        @DisplayName("Interval boundaries should be 17:45 - 18:00 CET")
        void shouldHaveCorrectBoundaries() {
            VolumeSeries series = volumeSeriesService.buildSeries(
                    UUID.randomUUID().toString(),
                    UUID.randomUUID().toString(),
                    start, end, TimeGranularity.MIN_15,
                    VOLUME_MW, ProfileType.BLOCK,
                    MaterializationStatus.FULL,
                    VolumeUnit.MWH_PER_PERIOD,
                    DELIVERY_TZ);

            VolumeInterval interval = series.intervals().get(0);
            assertEquals(LocalTime.of(17, 45), interval.intervalStart().toLocalTime());
            assertEquals(LocalTime.of(18, 0), interval.intervalEnd().toLocalTime());
        }

        @Test
        @DisplayName("Energy should be 15 MW x 1h = 15 MWh")
        void shouldCalculateCorrectEnergyPerPeriod() {
            VolumeSeries series = volumeSeriesService.buildSeries(
                    UUID.randomUUID().toString(),
                    UUID.randomUUID().toString(),
                    start, end, TimeGranularity.MIN_15,
                    VOLUME_MW,
                    ProfileType.BLOCK,
                    MaterializationStatus.FULL,
                    VolumeUnit.MWH_PER_PERIOD,
                    DELIVERY_TZ);

            VolumeInterval interval = series.intervals().get(0);
            assertEquals(VOLUME_MW, interval.volume());
            assertEquals(VOLUME_MW, interval.energy());
        }

        @Test
        @DisplayName("Energy should be 15 MW x .25h = 3.75 MWh")
        void shouldCalculateCorrectEnergyHourlyBasis() {
            VolumeSeries series = volumeSeriesService.buildSeries(
                    UUID.randomUUID().toString(),
                    UUID.randomUUID().toString(),
                    start, end, TimeGranularity.MIN_15,
                    VOLUME_MW,
                    ProfileType.BLOCK,
                    MaterializationStatus.FULL,
                    VolumeUnit.MW_CAPACITY,
                    DELIVERY_TZ);

            VolumeInterval interval = series.intervals().get(0);
            assertEquals(VOLUME_MW, interval.volume());
            assertEquals(0, new BigDecimal("3.750000")
                    .compareTo(interval.energy()));
        }

        @Test
        @DisplayName("Status should be FULL materialization with CONFIRMED intervals")
        void shouldBeFullyMaterialized() {
            VolumeSeries series = volumeSeriesService.buildSeries(
                    UUID.randomUUID().toString(),
                    UUID.randomUUID().toString(),
                    start, end,
                    TimeGranularity.MIN_15,
                    VOLUME_MW,
                    ProfileType.BLOCK,
                    MaterializationStatus.FULL,
                    VolumeUnit.MWH_PER_PERIOD,
                    DELIVERY_TZ);

            assertEquals(MaterializationStatus.FULL, series.materializationStatus());
            assertEquals(IntervalStatus.CONFIRMED, series.intervals().get(0).status());
            assertNull(series.getUnmaterializedWindow(),
                    "Fully materialized series should have no unmaterialized window");
        }
    }

    // ════════════════════════════════════════════════════════════════
    // TEST 2: Four 15-min intervals (17:00 - 18:00)
    // ════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Scenario 2: Four 15-min intervals, 15 MW, 17:00-18:00, 24 Apr 2026")
    class OneHourIn15MinIntervals {

        private final ZonedDateTime start =
                ZonedDateTime.of(2026, 4, 24, 17, 0, 0, 0, DELIVERY_TZ);
        private final ZonedDateTime end =
                ZonedDateTime.of(2026, 4, 24, 18, 0, 0, 0, DELIVERY_TZ);

        @Test
        @DisplayName("Should produce exactly 4 intervals")
        void shouldProduceFourIntervals() {
            VolumeSeries series = volumeSeriesService.buildSeries(
                    UUID.randomUUID().toString(),
                    UUID.randomUUID().toString(),
                    start, end, TimeGranularity.MIN_15,
                    VOLUME_MW,
                    ProfileType.BLOCK,
                    MaterializationStatus.FULL,
                    VolumeUnit.MWH_PER_PERIOD,
                    DELIVERY_TZ);

            assertEquals(4, series.intervals().size());
            assertEquals(4, series.totalExpectedIntervals());
        }

        @Test
        @DisplayName("Intervals should be contiguous and non-overlapping")
        void intervalsShouldBeContiguous() {
            VolumeSeries series = volumeSeriesService.buildSeries(
                    UUID.randomUUID().toString(),
                    UUID.randomUUID().toString(),
                    start, end, TimeGranularity.MIN_15,
                    VOLUME_MW, ProfileType.BLOCK, MaterializationStatus.FULL,
                    VolumeUnit.MWH_PER_PERIOD,
                    DELIVERY_TZ);

            List<VolumeInterval> intervals = series.intervals();
            for (int i = 0; i < intervals.size() - 1; i++) {
                assertEquals(intervals.get(i).intervalEnd(),
                        intervals.get(i + 1).intervalStart(),
                        "Gap detected between interval " + i + " and " + (i + 1));
            }
            assertEquals(start, intervals.get(0).intervalStart());
            assertEquals(end, intervals.get(intervals.size() - 1).intervalEnd());
        }

        @Test
        @DisplayName("Interval start times should be 17:00, 17:15, 17:30, 17:45")
        void shouldHaveCorrectStartTimes() {
            VolumeSeries series = volumeSeriesService.buildSeries(
                    UUID.randomUUID().toString(),
                    UUID.randomUUID().toString(),
                    start, end, TimeGranularity.MIN_15,
                    VOLUME_MW, ProfileType.BLOCK, MaterializationStatus.FULL,
                    VolumeUnit.MWH_PER_PERIOD,
                    DELIVERY_TZ);

            List<LocalTime> expectedStarts = List.of(
                    LocalTime.of(17, 0),
                    LocalTime.of(17, 15),
                    LocalTime.of(17, 30),
                    LocalTime.of(17, 45));

            List<LocalTime> actualStarts = series.intervals().stream()
                    .map(i -> i.intervalStart().toLocalTime())
                    .toList();

            assertEquals(expectedStarts, actualStarts);
        }

        @Test
        @DisplayName("Each interval energy = 3.75 MWh, total = 15 MWh")
        void shouldCalculateCorrectTotalEnergy() {
            VolumeSeries series = volumeSeriesService.buildSeries(
                    UUID.randomUUID().toString(),
                    UUID.randomUUID().toString(),
                    start, end, TimeGranularity.MIN_15,
                    VOLUME_MW, ProfileType.BLOCK, MaterializationStatus.FULL,
                    VolumeUnit.MW_CAPACITY,
                    DELIVERY_TZ);

            series.intervals().forEach(interval ->
                    assertEquals(0, new BigDecimal("3.750000")
                            .compareTo(interval.energy()),
                            "Each interval should be 3.75 MWh"));

            assertEquals(0, new BigDecimal("15")
                    .compareTo( volumeSeriesService.totalEnergy(series).setScale(0, RoundingMode.HALF_UP)),
                    "Total energy for 1 hour at 15 MW should be 15 MWh");
        }

        @Test
        @DisplayName("All intervals should belong to the same chunk month")
        void allIntervalsSameChunkMonth() {
            VolumeSeries series = volumeSeriesService.buildSeries(
                    UUID.randomUUID().toString(),
                    UUID.randomUUID().toString(),
                    start, end, TimeGranularity.MIN_15,
                    VOLUME_MW, ProfileType.BLOCK, MaterializationStatus.FULL,
                    VolumeUnit.MWH_PER_PERIOD,
                    DELIVERY_TZ);

            series.intervals().forEach(interval ->
                    assertEquals(YearMonth.of(2026, 4), interval.chunkMonth()));
        }
    }

    // ════════════════════════════════════════════════════════════════
    // TEST 3: Three 30-min intervals (17:00 - 18:30)
    // ════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Scenario 3: Three 30-min intervals, 15 MW, 17:00-18:30, 24 Apr 2026")
    class NinetyMinutesIn30MinIntervals {

        private final ZonedDateTime start =
                ZonedDateTime.of(2026, 4, 24, 17, 0, 0, 0, DELIVERY_TZ);
        private final ZonedDateTime end =
                ZonedDateTime.of(2026, 4, 24, 18, 30, 0, 0, DELIVERY_TZ);

        @Test
        @DisplayName("Should produce exactly 3 intervals")
        void shouldProduceThreeIntervals() {
            VolumeSeries series = volumeSeriesService.buildSeries(
                    UUID.randomUUID().toString(),
                    UUID.randomUUID().toString(),
                    start, end, TimeGranularity.MIN_30,
                    VOLUME_MW, ProfileType.BLOCK, MaterializationStatus.FULL,
                    VolumeUnit.MWH_PER_PERIOD,
                    DELIVERY_TZ);

            assertEquals(3, series.intervals().size());
            assertEquals(3, series.totalExpectedIntervals());
        }

        @Test
        @DisplayName("Interval boundaries: 17:00-17:30, 17:30-18:00, 18:00-18:30")
        void shouldHaveCorrectBoundaries() {
            VolumeSeries series = volumeSeriesService.buildSeries(
                    UUID.randomUUID().toString(),
                    UUID.randomUUID().toString(),
                    start, end, TimeGranularity.MIN_30,
                    VOLUME_MW, ProfileType.BLOCK, MaterializationStatus.FULL,
                    VolumeUnit.MWH_PER_PERIOD,
                    DELIVERY_TZ);

            List<VolumeInterval> intervals = series.intervals();

            assertEquals(LocalTime.of(17, 0), intervals.get(0).intervalStart().toLocalTime());
            assertEquals(LocalTime.of(17, 30), intervals.get(0).intervalEnd().toLocalTime());

            assertEquals(LocalTime.of(17, 30), intervals.get(1).intervalStart().toLocalTime());
            assertEquals(LocalTime.of(18, 0), intervals.get(1).intervalEnd().toLocalTime());

            assertEquals(LocalTime.of(18, 0), intervals.get(2).intervalStart().toLocalTime());
            assertEquals(LocalTime.of(18, 30), intervals.get(2).intervalEnd().toLocalTime());
        }

        @Test
        @DisplayName("Each interval energy = 7.50 MWh, total = 22.50 MWh")
        void shouldCalculateCorrectEnergyPerHour() {
            VolumeSeries series = volumeSeriesService.buildSeries(
                    UUID.randomUUID().toString(),
                    UUID.randomUUID().toString(),
                    start, end, TimeGranularity.MIN_30,
                    VOLUME_MW, ProfileType.BLOCK, MaterializationStatus.FULL,
                    VolumeUnit.MW_CAPACITY,
                    DELIVERY_TZ);

            series.intervals().forEach(interval ->
                    assertEquals(0, new BigDecimal("7.500000")
                            .compareTo(interval.energy())));

            BigDecimal expectedTotal = new BigDecimal("22.50");
            assertEquals(0, expectedTotal.compareTo(
                     volumeSeriesService.totalEnergy(series).setScale(2, RoundingMode.HALF_UP)));
        }

        @Test
        @DisplayName("Each interval energy = 15 MWh, total = 45 MWh")
        void shouldCalculateCorrectEnergyPerPeriod() {
            VolumeSeries series = volumeSeriesService.buildSeries(
                    UUID.randomUUID().toString(),
                    UUID.randomUUID().toString(),
                    start, end, TimeGranularity.MIN_30,
                    VOLUME_MW, ProfileType.BLOCK, MaterializationStatus.FULL,
                    VolumeUnit.MWH_PER_PERIOD,
                    DELIVERY_TZ);

            series.intervals().forEach(interval ->
                    assertEquals(0, new BigDecimal("15")
                            .compareTo(interval.energy())));

            BigDecimal expectedTotal = new BigDecimal("45.0");
            assertEquals(0, expectedTotal.compareTo(
                    volumeSeriesService.totalEnergy(series).setScale(2, RoundingMode.HALF_UP)));
        }
    }

    // ════════════════════════════════════════════════════════════════
    // TEST 4: Two 30-min intervals (17:00 - 18:00)
    // ════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Scenario 4: Two 30-min intervals, 15 MW, 17:00-18:00, 24 Apr 2026")
    class OneHourIn30MinIntervals {

        private final ZonedDateTime start =
                ZonedDateTime.of(2026, 4, 24, 17, 0, 0, 0, DELIVERY_TZ);
        private final ZonedDateTime end =
                ZonedDateTime.of(2026, 4, 24, 18, 0, 0, 0, DELIVERY_TZ);

        @Test
        @DisplayName("Should produce exactly 2 intervals")
        void shouldProduceTwoIntervals() {
            VolumeSeries series = volumeSeriesService.buildSeries(
                    UUID.randomUUID().toString(),
                    UUID.randomUUID().toString(),
                    start, end, TimeGranularity.MIN_30,
                    VOLUME_MW, ProfileType.BLOCK, MaterializationStatus.FULL,
                    VolumeUnit.MWH_PER_PERIOD,
                    DELIVERY_TZ);

            assertEquals(2, series.intervals().size());
            assertEquals(2, series.totalExpectedIntervals());
        }

        @Test
        @DisplayName("Total energy should match: 15 MW x 1h = 15 MWh (same as Scenario 2)")
        void  totalEnergyShouldMatchHourlyEquivalent() {
            VolumeSeries series30 = volumeSeriesService.buildSeries(
                    UUID.randomUUID().toString(),
                    UUID.randomUUID().toString(),
                    start, end, TimeGranularity.MIN_30,
                    VOLUME_MW, ProfileType.BLOCK, MaterializationStatus.FULL,
                    VolumeUnit.MW_CAPACITY,
                    DELIVERY_TZ);

            VolumeSeries series15 = volumeSeriesService.buildSeries(
                    UUID.randomUUID().toString(),
                    UUID.randomUUID().toString(),
                    start, end, TimeGranularity.MIN_15,
                    VOLUME_MW, ProfileType.BLOCK, MaterializationStatus.FULL,
                    VolumeUnit.MW_CAPACITY,
                    DELIVERY_TZ);

            BigDecimal total30 =  volumeSeriesService.totalEnergy(series30);
            BigDecimal total15 =  volumeSeriesService.totalEnergy(series15);

            assertEquals(0, total30.compareTo(total15),
                    "Total energy must be identical regardless of granularity: " +
                    "30-min=" + total30 + " vs 15-min=" + total15);

            assertEquals(0, new BigDecimal("15")
                    .compareTo(total30.setScale(0, RoundingMode.HALF_UP)),
                    "15 MW for 1 hour = 15 MWh");
        }

        @Test
        @DisplayName("Each interval should be 7.50 MWh")
        void eachIntervalCorrectEnergy() {
            VolumeSeries series = volumeSeriesService.buildSeries(
                    UUID.randomUUID().toString(),
                    UUID.randomUUID().toString(),
                    start, end, TimeGranularity.MIN_30,
                    VOLUME_MW, ProfileType.BLOCK, MaterializationStatus.FULL,
                    VolumeUnit.MW_CAPACITY,
                    DELIVERY_TZ);

            series.intervals().forEach(interval ->
                    assertEquals(0, new BigDecimal("7.500000")
                            .compareTo(interval.energy())));
        }
    }

    // ════════════════════════════════════════════════════════════════
    // TEST 5: Full year PPA at 15-min (17:00 24-Apr-2026 to 17:00 24-Apr-2027)
    // Two-tier materialization scenario.
    // ════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Scenario 5: 1-year PPA, 15 MW, 15-min granularity, 24 Apr 2026 - 24 Apr 2027")
    class OneYearPPA {

        private final ZonedDateTime start =
                ZonedDateTime.of(2026, 4, 24, 17, 0, 0, 0, DELIVERY_TZ);
        private final ZonedDateTime end =
                ZonedDateTime.of(2027, 4, 24, 17, 0, 0, 0, DELIVERY_TZ);

        @Test
        @DisplayName("Full materialization should produce ~35,040 intervals (365 days x 96)")
        void shouldProduceCorrectIntervalCount() {
            VolumeSeries series = volumeSeriesService.buildSeries(
                    UUID.randomUUID().toString(),
                    UUID.randomUUID().toString(),
                    start, end, TimeGranularity.MIN_15,
                    VOLUME_MW, ProfileType.BASELOAD, MaterializationStatus.FULL,
                    VolumeUnit.MWH_PER_PERIOD,
                    DELIVERY_TZ);

            int expected = series.calculateExpectedIntervals();
            assertEquals(expected, series.intervals().size());

            // 365 days x 96 intervals/day = 35,040 baseline
            // DST spring-forward (Mar 2027): -4 intervals
            // DST fall-back (Oct 2026): +4 intervals
            // Net: cancels out for a full year
            assertTrue(expected >= 35_036 && expected <= 35_044,
                    "Expected ~35,040 intervals for a 1-year PPA at 15-min, got " + expected);
        }

        @Test
        @DisplayName("All intervals should be contiguous with no gaps")
        void shouldBeContiguous() {
            VolumeSeries series = volumeSeriesService.buildSeries(
                    UUID.randomUUID().toString(),
                    UUID.randomUUID().toString(),
                    start, end, TimeGranularity.MIN_15,
                    VOLUME_MW, ProfileType.BASELOAD, MaterializationStatus.FULL,
                    VolumeUnit.MWH_PER_PERIOD,
                    DELIVERY_TZ);

            List<VolumeInterval> intervals = series.intervals();
            assertEquals(start, intervals.get(0).intervalStart());
            assertEquals(end, intervals.get(intervals.size() - 1).intervalEnd());

            for (int i = 0; i < intervals.size() - 1; i++) {
                assertEquals(intervals.get(i).intervalEnd(),
                        intervals.get(i + 1).intervalStart(),
                        "Gap at interval " + i);
            }
        }

        @Test
        @DisplayName("Total energy should be ~15 MW x 8760h = 131,400 MWh (within DST tolerance)")
        void shouldCalculateCorrectAnnualEnergy() {
            VolumeSeries series = volumeSeriesService.buildSeries(
                    UUID.randomUUID().toString(),
                    UUID.randomUUID().toString(),
                    start, end, TimeGranularity.MIN_15,
                    VOLUME_MW, ProfileType.BASELOAD, MaterializationStatus.FULL,
                    VolumeUnit.MW_CAPACITY,
                    DELIVERY_TZ);

            BigDecimal total =  volumeSeriesService.totalEnergy(series);
            BigDecimal expected = new BigDecimal("131400");
            BigDecimal tolerance = new BigDecimal("15");

            assertTrue(total.subtract(expected).abs().compareTo(tolerance) <= 0,
                    "Annual energy should be ~131,400 MWh, got " + total);
        }

        @Test
        @DisplayName("DST fall-back (Oct 25 2026): 25-hour day produces 100 intervals")
        void shouldHandleDstFallBack() {
            ZonedDateTime dstDayStart =
                    ZonedDateTime.of(2026, 10, 25, 0, 0, 0, 0, DELIVERY_TZ);
            ZonedDateTime dstDayEnd =
                    ZonedDateTime.of(2026, 10, 26, 0, 0, 0, 0, DELIVERY_TZ);

            VolumeSeries dstDaySeries = volumeSeriesService.buildSeries(
                    UUID.randomUUID().toString(),
                    UUID.randomUUID().toString(),
                    dstDayStart, dstDayEnd, TimeGranularity.MIN_15,
                    VOLUME_MW, ProfileType.BASELOAD, MaterializationStatus.FULL,
                    VolumeUnit.MW_CAPACITY,
                    DELIVERY_TZ);

            assertEquals(100, dstDaySeries.intervals().size(),
                    "DST fall-back day should have 100 fifteen-minute intervals (25 hours)");

            BigDecimal dstEnergy =  volumeSeriesService.totalEnergy(dstDaySeries);
            assertEquals(0, new BigDecimal("375")
                    .compareTo(dstEnergy.setScale(0, RoundingMode.HALF_UP)),
                    "DST fall-back day energy should be 375 MWh (25 hours x 15 MW)");
        }

        @Test
        @DisplayName("DST spring-forward (Mar 28 2027): 23-hour day produces 92 intervals")
        void shouldHandleDstSpringForward() {
            ZonedDateTime dstDayStart =
                    ZonedDateTime.of(2027, 3, 28, 0, 0, 0, 0, DELIVERY_TZ);
            ZonedDateTime dstDayEnd =
                    ZonedDateTime.of(2027, 3, 29, 0, 0, 0, 0, DELIVERY_TZ);

            VolumeSeries dstDaySeries = volumeSeriesService.buildSeries(
                    UUID.randomUUID().toString(),
                    UUID.randomUUID().toString(),
                    dstDayStart, dstDayEnd, TimeGranularity.MIN_15,
                    VOLUME_MW, ProfileType.BASELOAD, MaterializationStatus.FULL,
                    VolumeUnit.MW_CAPACITY,
                    DELIVERY_TZ);

            assertEquals(92, dstDaySeries.intervals().size(),
                    "DST spring-forward day should have 92 fifteen-minute intervals (23 hours)");

            BigDecimal dstEnergy =  volumeSeriesService.totalEnergy(dstDaySeries);
            assertEquals(0, new BigDecimal("345")
                    .compareTo(dstEnergy.setScale(0, RoundingMode.HALF_UP)),
                    "DST spring-forward day energy should be 345 MWh (23 hours x 15 MW)");
        }

        // ── Two-Tier Materialization Tests ──

        @Test
        @DisplayName("Partial materialization: M+3 window should only generate near-term intervals")
        void shouldSupportPartialMaterialization() {
            YearMonth matThrough = YearMonth.of(2026, 7);

            VolumeSeries series = volumeSeriesService.buildPartialSeries(
                    UUID.randomUUID().toString(),
                    UUID.randomUUID().toString(),
                    start, end, TimeGranularity.MIN_15,
                    VOLUME_MW, ProfileType.BASELOAD,
                    VolumeUnit.MWH_PER_PERIOD,
                    DELIVERY_TZ, matThrough);

            assertEquals(MaterializationStatus.PARTIAL, series.materializationStatus());
            assertEquals(matThrough, series.materializedThrough());
            assertTrue(series.materializedIntervalCount()
                            < series.totalExpectedIntervals(),
                    "Partial materialization should have fewer intervals than full");

            DeliveryWindow unmaterialized = series.getUnmaterializedWindow();
            assertNotNull(unmaterialized, "Should have an unmaterialized window");
            assertEquals(LocalDate.of(2026, 8, 1),
                    unmaterialized.start().toLocalDate());
            assertEquals(end, unmaterialized.end());
        }

        @Test
        @DisplayName("Chunk materialization should extend the materialized window")
        void shouldMaterializeChunkAndExtendWindow() {
            YearMonth matThrough = YearMonth.of(2026, 7);

            VolumeSeries partial = volumeSeriesService.buildPartialSeries(
                    UUID.randomUUID().toString(),
                    UUID.randomUUID().toString(),
                    start, end, TimeGranularity.MIN_15,
                    VOLUME_MW, ProfileType.BASELOAD,
                    VolumeUnit.MWH_PER_PERIOD,
                    DELIVERY_TZ, matThrough);

            int countBefore = partial.materializedIntervalCount();

            // Materialize August 2026 chunk
            VolumeSeries extended = volumeSeriesService.materializeChunk(
                    partial, YearMonth.of(2026, 8), VOLUME_MW);

            // materializedThrough advanced to August
            assertEquals(YearMonth.of(2026, 8), extended.materializedThrough());
            assertEquals(MaterializationStatus.PARTIAL, extended.materializationStatus());

            // Interval count increased by August's intervals (31 days × 96)
            int augIntervals = 31 * 96;
            assertEquals(countBefore + augIntervals, extended.materializedIntervalCount());

            // Intervals are contiguous at the boundary
            List<VolumeInterval> intervals = extended.intervals();
            for (int i = 0; i < intervals.size() - 1; i++) {
                assertEquals(intervals.get(i).intervalEnd(),
                        intervals.get(i + 1).intervalStart(),
                        "Gap at interval " + i);
            }

            // Unmaterialized window starts at Sep 1
            DeliveryWindow unmaterialized = extended.getUnmaterializedWindow();
            assertNotNull(unmaterialized);
            assertEquals(LocalDate.of(2026, 9, 1),
                    unmaterialized.start().toLocalDate());
        }

        @Test
        @DisplayName("Completing all chunks should promote status to FULL")
        void shouldCompleteFullMaterializationViaChunks() {
            // Use a short 2-month delivery for fast test
            ZonedDateTime shortStart = ZonedDateTime.of(2026, 5, 1, 0, 0, 0, 0, DELIVERY_TZ);
            ZonedDateTime shortEnd = ZonedDateTime.of(2026, 7, 1, 0, 0, 0, 0, DELIVERY_TZ);

            // Partial: materialize May only
            VolumeSeries partial = volumeSeriesService.buildPartialSeries(
                    UUID.randomUUID().toString(),
                    UUID.randomUUID().toString(),
                    shortStart, shortEnd, TimeGranularity.MIN_15,
                    VOLUME_MW, ProfileType.BASELOAD,
                    VolumeUnit.MWH_PER_PERIOD,
                    DELIVERY_TZ, YearMonth.of(2026, 5));

            assertEquals(MaterializationStatus.PARTIAL, partial.materializationStatus());

            // Materialize June chunk → should complete
            VolumeSeries completed = volumeSeriesService.materializeChunk(
                    partial, YearMonth.of(2026, 6), VOLUME_MW);

            assertEquals(MaterializationStatus.FULL, completed.materializationStatus(),
                    "Should be FULL after all chunks materialized");
            assertNull(completed.materializedThrough(),
                    "materializedThrough should be null when FULL");
            assertNull(completed.getUnmaterializedWindow(),
                    "No unmaterialized window when FULL");
            assertEquals(completed.totalExpectedIntervals(),
                    completed.materializedIntervalCount(),
                    "Materialized count should equal total expected");
        }

        @Test
        @DisplayName("Chunk months should correctly partition intervals across 13 months")
        void chunkMonthsShouldPartition() {
            VolumeSeries series = volumeSeriesService.buildSeries(
                    UUID.randomUUID().toString(),
                    UUID.randomUUID().toString(),
                    start, end, TimeGranularity.MIN_15,
                    VOLUME_MW, ProfileType.BASELOAD, MaterializationStatus.FULL,
                    VolumeUnit.MWH_PER_PERIOD,
                    DELIVERY_TZ);

            var byChunk = series.intervals().stream()
                    .collect(Collectors.groupingBy(
                            VolumeInterval::chunkMonth,
                            Collectors.counting()));

            // Apr 2026 (partial) through Apr 2027 (partial) = 13 months
            assertEquals(13, byChunk.size(),
                    "Should span 13 months (Apr 2026 partial through Apr 2027 partial)");

            // First partial month (Apr 24 17:00 to Apr 30 24:00)
            long aprCount = byChunk.get(YearMonth.of(2026, 4));
            assertTrue(aprCount > 0 && aprCount < 96 * 30,
                    "April 2026 should be a partial month, got " + aprCount);

            // Full month (May 2026): 31 days x 96 = 2976
            long mayCount = byChunk.get(YearMonth.of(2026, 5));
            assertEquals(31L * 96, mayCount,
                    "May 2026 should have exactly 2976 intervals (31 x 96)");

            // Full month (June 2026): 30 days x 96 = 2880
            long junCount = byChunk.get(YearMonth.of(2026, 6));
            assertEquals(30L * 96, junCount,
                    "June 2026 should have exactly 2880 intervals (30 x 96)");
        }

        @Test
        @DisplayName("Formula should be attachable to PPA (recipe for future materialization)")
        void shouldHaveFormulaForPPA() {
            VolumeSeries series = volumeSeriesService.buildSeries(
                    UUID.randomUUID().toString(),
                    UUID.randomUUID().toString(),
                    start, end, TimeGranularity.MIN_15,
                    VOLUME_MW, ProfileType.BASELOAD, MaterializationStatus.PARTIAL,
                    VolumeUnit.MWH_PER_PERIOD,
                    DELIVERY_TZ);

            VolumeFormula formula = new VolumeFormula(
                    UUID.randomUUID(), series.id(),
                    VOLUME_MW, new BigDecimal("12"), new BigDecimal("18"),
                    null, null, null, null, null);

            VolumeSeries seriesWithFormula = series.withFormula(formula);

            assertNotNull(seriesWithFormula.formula());
            assertEquals(VOLUME_MW, seriesWithFormula.formula().baseVolume());
            assertEquals(new BigDecimal("12"), seriesWithFormula.formula().minVolume());
            assertEquals(new BigDecimal("18"), seriesWithFormula.formula().maxVolume());
        }

        @Test
        @DisplayName("Fully materialized series should have no unmaterialized window")
        void fullyMaterializedHasNoUnmaterializedWindow() {
            VolumeSeries series = volumeSeriesService.buildSeries(
                    UUID.randomUUID().toString(),
                    UUID.randomUUID().toString(),
                    start, end, TimeGranularity.MIN_15,
                    VOLUME_MW, ProfileType.BASELOAD, MaterializationStatus.FULL,
                    VolumeUnit.MWH_PER_PERIOD,
                    DELIVERY_TZ);

            assertNull(series.getUnmaterializedWindow());
        }
    }

    // ════════════════════════════════════════════════════════════════
    // Cross-cutting validation tests
    // ════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Cross-cutting: granularity-independent invariants")
    class CrossCuttingTests {

        private final ZonedDateTime start =
                ZonedDateTime.of(2026, 4, 24, 17, 0, 0, 0, DELIVERY_TZ);
        private final ZonedDateTime end =
                ZonedDateTime.of(2026, 4, 24, 18, 0, 0, 0, DELIVERY_TZ);

        @Test
        @DisplayName("Total energy must be identical across all sub-hourly granularities for MW_CAPACITY")
        void energyInvariantAcrossGranularities() {
            VolumeSeries series5 = volumeSeriesService.buildSeries(
                    UUID.randomUUID().toString(),
                    UUID.randomUUID().toString(),
                    start, end,
                    TimeGranularity.MIN_5, VOLUME_MW, ProfileType.BASELOAD,
                    MaterializationStatus.FULL,
                    VolumeUnit.MW_CAPACITY,
                    DELIVERY_TZ);
            VolumeSeries series15 = volumeSeriesService.buildSeries(
                    UUID.randomUUID().toString(),
                    UUID.randomUUID().toString(),
                    start, end,
                    TimeGranularity.MIN_15, VOLUME_MW, ProfileType.BASELOAD,
                    MaterializationStatus.FULL,
                    VolumeUnit.MW_CAPACITY,
                    DELIVERY_TZ);
            VolumeSeries series30 = volumeSeriesService.buildSeries(
                    UUID.randomUUID().toString(),
                    UUID.randomUUID().toString(),
                    start, end,
                    TimeGranularity.MIN_30, VOLUME_MW, ProfileType.BASELOAD,
                    MaterializationStatus.FULL,
                    VolumeUnit.MW_CAPACITY,
                    DELIVERY_TZ);
            VolumeSeries series60 = volumeSeriesService.buildSeries(
                    UUID.randomUUID().toString(),
                    UUID.randomUUID().toString(),
                    start, end,
                    TimeGranularity.HOURLY, VOLUME_MW, ProfileType.BASELOAD,
                    MaterializationStatus.FULL,
                    VolumeUnit.MW_CAPACITY,
                    DELIVERY_TZ);

            BigDecimal energy5 =  volumeSeriesService.totalEnergy(series5);
            BigDecimal energy15 =  volumeSeriesService.totalEnergy(series15);
            BigDecimal energy30 =  volumeSeriesService.totalEnergy(series30);
            BigDecimal energy60 =  volumeSeriesService.totalEnergy(series60);

            assertEquals(0, energy5.compareTo(energy15),
                    "5-min and 15-min total energy must match");
            assertEquals(0, energy15.compareTo(energy30),
                    "15-min and 30-min total energy must match");
            assertEquals(0, energy30.compareTo(energy60),
                    "30-min and 60-min total energy must match");

            assertEquals(0, new BigDecimal("15").compareTo(
                    energy60.setScale(0, RoundingMode.HALF_UP)));
        }

        @Test
        @DisplayName("Interval count should scale inversely with granularity")
        void intervalCountScalesWithGranularity() {
            assertEquals(12, volumeSeriesService.buildSeries(
                            UUID.randomUUID().toString(),
                            UUID.randomUUID().toString(),
                    start, end, TimeGranularity.MIN_5,
                    VOLUME_MW, ProfileType.BASELOAD, MaterializationStatus.FULL,
                    VolumeUnit.MWH_PER_PERIOD,
                            DELIVERY_TZ)
                    .intervals().size(), "5-min: 12 intervals");

            assertEquals(4, volumeSeriesService.buildSeries(
                            UUID.randomUUID().toString(),
                            UUID.randomUUID().toString(),
                    start, end, TimeGranularity.MIN_15,
                    VOLUME_MW, ProfileType.BASELOAD, MaterializationStatus.FULL,
                    VolumeUnit.MWH_PER_PERIOD,
                            DELIVERY_TZ)
                    .intervals().size(), "15-min: 4 intervals");

            assertEquals(2, volumeSeriesService.buildSeries(
                            UUID.randomUUID().toString(),
                            UUID.randomUUID().toString(),
                            start, end, TimeGranularity.MIN_30,
                    VOLUME_MW, ProfileType.BASELOAD, MaterializationStatus.FULL,
                    VolumeUnit.MWH_PER_PERIOD,
                            DELIVERY_TZ)
                    .intervals().size(), "30-min: 2 intervals");

            assertEquals(1, volumeSeriesService.buildSeries(
                            UUID.randomUUID().toString(),
                            UUID.randomUUID().toString(),
                    start, end, TimeGranularity.HOURLY,
                    VOLUME_MW, ProfileType.BASELOAD, MaterializationStatus.FULL,
                    VolumeUnit.MWH_PER_PERIOD,
                            DELIVERY_TZ)
                    .intervals().size(), "Hourly: 1 interval");
        }

        @Test
        @DisplayName("Bi-temporal timestamps should be set")
        void biTemporalTimestampsShouldBeSet() {
            VolumeSeries series = volumeSeriesService.buildSeries(
                    UUID.randomUUID().toString(),
                    UUID.randomUUID().toString(),
                    start, end,
                    TimeGranularity.MIN_15, VOLUME_MW, ProfileType.BASELOAD,
                    MaterializationStatus.FULL,
                    VolumeUnit.MWH_PER_PERIOD,
                    DELIVERY_TZ);

            assertNotNull(series.transactionTime(),
                    "Transaction time must be set");
            assertNotNull(series.validTime(),
                    "Valid time must be set");
        }

        @Test
        @DisplayName("MWH_PER_PERIOD: energy equals volume regardless of interval duration")
        void mwhPerPeriodEnergyEqualsVolume() {
            // 15-min intervals: 4 intervals, each energy = 15 MWh, total = 60 MWh
            VolumeSeries series15 = volumeSeriesService.buildSeries(
                    UUID.randomUUID().toString(),
                    UUID.randomUUID().toString(),
                    start, end,
                    TimeGranularity.MIN_15, VOLUME_MW, ProfileType.BASELOAD,
                    MaterializationStatus.FULL,
                    VolumeUnit.MWH_PER_PERIOD,
                    DELIVERY_TZ);

            for (VolumeInterval iv : series15.intervals()) {
                assertEquals(0, VOLUME_MW.compareTo(iv.energy()),
                        "MWH_PER_PERIOD: each interval energy must equal volume");
            }
            assertEquals(0, new BigDecimal("60").compareTo(
                    volumeSeriesService.totalEnergy(series15)),
                    "MWH_PER_PERIOD 15-min: total energy = 4 × 15 = 60 MWh");

            // 30-min intervals: 2 intervals, each energy = 15 MWh, total = 30 MWh
            VolumeSeries series30 = volumeSeriesService.buildSeries(
                    UUID.randomUUID().toString(),
                    UUID.randomUUID().toString(),
                    start, end,
                    TimeGranularity.MIN_30, VOLUME_MW, ProfileType.BASELOAD,
                    MaterializationStatus.FULL,
                    VolumeUnit.MWH_PER_PERIOD,
                    DELIVERY_TZ);

            for (VolumeInterval iv : series30.intervals()) {
                assertEquals(0, VOLUME_MW.compareTo(iv.energy()),
                        "MWH_PER_PERIOD: each interval energy must equal volume");
            }
            assertEquals(0, new BigDecimal("30").compareTo(
                    volumeSeriesService.totalEnergy(series30)),
                    "MWH_PER_PERIOD 30-min: total energy = 2 × 15 = 30 MWh");
        }

        @Test
        @DisplayName("MW_CAPACITY vs MWH_PER_PERIOD: different energy for same volume value")
        void mwCapacityVsMwhPerPeriodComparison() {
            VolumeSeries mwSeries = volumeSeriesService.buildSeries(
                    UUID.randomUUID().toString(),
                    UUID.randomUUID().toString(),
                    start, end,
                    TimeGranularity.MIN_15, VOLUME_MW, ProfileType.BASELOAD,
                    MaterializationStatus.FULL,
                    VolumeUnit.MW_CAPACITY,
                    DELIVERY_TZ);
            VolumeSeries mwhSeries = volumeSeriesService.buildSeries(
                    UUID.randomUUID().toString(),
                    UUID.randomUUID().toString(),
                    start, end,
                    TimeGranularity.MIN_15, VOLUME_MW, ProfileType.BASELOAD,
                    MaterializationStatus.FULL,
                    VolumeUnit.MWH_PER_PERIOD,
                    DELIVERY_TZ);

            BigDecimal mwIntervalEnergy = mwSeries.intervals().get(0).energy();
            BigDecimal mwhIntervalEnergy = mwhSeries.intervals().get(0).energy();

            // MW_CAPACITY: 15 MW × 0.25h = 3.75 MWh per 15-min interval
            assertEquals(0, new BigDecimal("3.750000").compareTo(mwIntervalEnergy),
                    "MW_CAPACITY 15-min interval energy should be 3.75 MWh");
            // MWH_PER_PERIOD: energy = volume = 15 MWh per interval
            assertEquals(0, new BigDecimal("15").compareTo(mwhIntervalEnergy),
                    "MWH_PER_PERIOD 15-min interval energy should be 15 MWh");

            assertNotEquals(0, mwIntervalEnergy.compareTo(mwhIntervalEnergy),
                    "MW_CAPACITY and MWH_PER_PERIOD must produce different per-interval energy");
        }

        @Test
        @DisplayName("TradeLegId: unique per leg, shared tradeId")
        void tradeLegIdUniqueness() {
            VolumeSeries leg1 = volumeSeriesService.buildSeries(
                    UUID.randomUUID().toString(),
                    UUID.randomUUID().toString(),
                    start, end,
                    TimeGranularity.MIN_15, VOLUME_MW, ProfileType.BASELOAD,
                    MaterializationStatus.FULL,
                    VolumeUnit.MW_CAPACITY,
                    DELIVERY_TZ);
            VolumeSeries leg2 = volumeSeriesService.buildSeries(
                    UUID.randomUUID().toString(),
                    UUID.randomUUID().toString(),
                    start, end,
                    TimeGranularity.MIN_15, VOLUME_MW, ProfileType.BASELOAD,
                    MaterializationStatus.FULL,
                    VolumeUnit.MW_CAPACITY,
                    DELIVERY_TZ);

            // Both legs must have non-null IDs
            assertNotNull(leg1.tradeId(), "Leg 1 tradeId must not be null");
            assertNotNull(leg1.tradeLegId(), "Leg 1 tradeLegId must not be null");
            assertNotNull(leg2.tradeId(), "Leg 2 tradeId must not be null");
            assertNotNull(leg2.tradeLegId(), "Leg 2 tradeLegId must not be null");

            // Different series have distinct tradeLegIds
            assertNotEquals(leg1.tradeLegId(), leg2.tradeLegId(),
                    "Two legs must have distinct tradeLegId values");
        }

        @Test
        @DisplayName("MONTHLY granularity should throw on getFixedDuration()")
        void monthlyGranularityShouldThrow() {
            assertThrows(UnsupportedOperationException.class,
                    () -> TimeGranularity.MONTHLY.getFixedDuration(),
                    "MONTHLY has variable length and should not return a fixed duration");
        }

        @Test
        @DisplayName("DeliveryWindow should reject end before start")
        void deliveryWindowShouldRejectInvalidRange() {
            ZonedDateTime earlier = ZonedDateTime.of(2026, 4, 24, 0, 0, 0, 0, DELIVERY_TZ);
            ZonedDateTime later = ZonedDateTime.of(2026, 4, 25, 0, 0, 0, 0, DELIVERY_TZ);

            assertThrows(IllegalArgumentException.class,
                    () -> new DeliveryWindow(later, earlier),
                    "DeliveryWindow should reject end before start");
        }
    }

    // ════════════════════════════════════════════════════════════════
    // Cascade materialization tests
    // ════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Cascade: multi-granularity materialization for long-term PPAs")
    class CascadeMaterializationTests {

        // 10-year PPA: Apr 24 2026 → Apr 24 2036, base=MIN_15
        private final ZonedDateTime deliveryStart =
                ZonedDateTime.of(2026, 4, 24, 0, 0, 0, 0, DELIVERY_TZ);
        private final ZonedDateTime deliveryEnd =
                ZonedDateTime.of(2036, 4, 24, 0, 0, 0, 0, DELIVERY_TZ);
        // Week ends Sunday midnight → Monday Apr 27
        private final LocalDate weekEnd = LocalDate.of(2026, 4, 27);

        @Test
        @DisplayName("buildCascadeSeries should produce three tiers with correct properties")
        void shouldBuildCascadeWithThreeTiers() {
            CascadeResult result = volumeSeriesService.buildCascadeSeries(
                    "TRADE-1", "LEG-1",
                    deliveryStart, deliveryEnd,
                    TimeGranularity.MIN_15, VOLUME_MW, ProfileType.BASELOAD,
                    VolumeUnit.MW_CAPACITY, DELIVERY_TZ, weekEnd);

            // Near-term: Apr 24 → Apr 27 at MIN_15, FULL
            VolumeSeries near = result.nearTerm();
            assertEquals(CascadeTier.NEAR_TERM, near.cascadeTier());
            assertEquals(TimeGranularity.MIN_15, near.granularity());
            assertEquals(MaterializationStatus.FULL, near.materializationStatus());
            assertEquals(deliveryStart, near.deliveryStart());
            assertEquals(weekEnd.atStartOfDay(DELIVERY_TZ), near.deliveryEnd());
            // 3 days × 96 intervals/day = 288
            assertEquals(3 * 96, near.intervals().size(),
                    "Near-term should have 3 days × 96 = 288 intervals");
            assertEquals(near.intervals().size(), near.totalExpectedIntervals());
            assertEquals(near.intervals().size(), near.materializedIntervalCount());

            // Medium-term: Apr 27 → Jul 1 at DAILY, PENDING
            VolumeSeries medium = result.mediumTerm();
            assertEquals(CascadeTier.MEDIUM_TERM, medium.cascadeTier());
            assertEquals(TimeGranularity.DAILY, medium.granularity());
            assertEquals(MaterializationStatus.PENDING, medium.materializationStatus());
            assertEquals(weekEnd.atStartOfDay(DELIVERY_TZ), medium.deliveryStart());
            // M+3 from Apr = Jul
            assertEquals(ZonedDateTime.of(2026, 7, 1, 0, 0, 0, 0, DELIVERY_TZ),
                    medium.deliveryEnd());
            assertEquals(0, medium.intervals().size(), "Medium-term starts with no intervals");
            // Apr 27,28,29,30 = 4 days + May 31 + Jun 30 = 65 days total
            int expectedMediumDays = 4 + 31 + 30; // rest of Apr + May + Jun
            assertEquals(expectedMediumDays, medium.totalExpectedIntervals(),
                    "Medium-term expected daily intervals");

            // Long-term: Jul 1 → Apr 24 2036 at MONTHLY, PENDING
            VolumeSeries longT = result.longTerm();
            assertEquals(CascadeTier.LONG_TERM, longT.cascadeTier());
            assertEquals(TimeGranularity.MONTHLY, longT.granularity());
            assertEquals(MaterializationStatus.PENDING, longT.materializationStatus());
            assertEquals(ZonedDateTime.of(2026, 7, 1, 0, 0, 0, 0, DELIVERY_TZ),
                    longT.deliveryStart());
            assertEquals(deliveryEnd, longT.deliveryEnd());
            assertEquals(0, longT.intervals().size(), "Long-term starts with no intervals");
            // Jul 2026 → Apr 2036 = 117 months
            assertEquals(117, longT.totalExpectedIntervals(),
                    "Long-term expected monthly intervals");

            // All share same tradeId/tradeLegId
            assertEquals("TRADE-1", near.tradeId());
            assertEquals("TRADE-1", medium.tradeId());
            assertEquals("TRADE-1", longT.tradeId());
            assertEquals("LEG-1", near.tradeLegId());
            assertEquals("LEG-1", medium.tradeLegId());
            assertEquals("LEG-1", longT.tradeLegId());
        }

        @Test
        @DisplayName("materializeMediumTermChunk should generate daily intervals")
        void shouldMaterializeMediumTermChunk() {
            CascadeResult cascade = volumeSeriesService.buildCascadeSeries(
                    "TRADE-1", "LEG-1",
                    deliveryStart, deliveryEnd,
                    TimeGranularity.MIN_15, VOLUME_MW, ProfileType.BASELOAD,
                    VolumeUnit.MW_CAPACITY, DELIVERY_TZ, weekEnd);

            // Materialize May 2026 chunk (31 days)
            VolumeSeries updated = volumeSeriesService.materializeMediumTermChunk(
                    cascade.mediumTerm(), YearMonth.of(2026, 5), VOLUME_MW);

            assertEquals(MaterializationStatus.PARTIAL, updated.materializationStatus());
            assertEquals(31, updated.materializedIntervalCount(),
                    "May has 31 daily intervals");
            assertEquals(YearMonth.of(2026, 5), updated.materializedThrough());

            // Verify contiguity of daily intervals
            List<VolumeInterval> intervals = updated.intervals();
            for (int i = 0; i < intervals.size() - 1; i++) {
                assertEquals(intervals.get(i).intervalEnd(),
                        intervals.get(i + 1).intervalStart(),
                        "Gap at daily interval " + i);
            }

            // First interval starts May 1
            assertEquals(LocalDate.of(2026, 5, 1),
                    intervals.get(0).intervalStart().toLocalDate());
        }

        @Test
        @DisplayName("materializeLongTermChunk should generate a monthly interval")
        void shouldMaterializeLongTermChunk() {
            CascadeResult cascade = volumeSeriesService.buildCascadeSeries(
                    "TRADE-1", "LEG-1",
                    deliveryStart, deliveryEnd,
                    TimeGranularity.MIN_15, VOLUME_MW, ProfileType.BASELOAD,
                    VolumeUnit.MW_CAPACITY, DELIVERY_TZ, weekEnd);

            // Materialize Aug 2026 chunk
            VolumeSeries updated = volumeSeriesService.materializeLongTermChunk(
                    cascade.longTerm(), YearMonth.of(2026, 8), VOLUME_MW);

            assertEquals(1, updated.materializedIntervalCount());
            assertEquals(YearMonth.of(2026, 8), updated.materializedThrough());

            VolumeInterval monthly = updated.intervals().get(0);
            assertEquals(ZonedDateTime.of(2026, 8, 1, 0, 0, 0, 0, DELIVERY_TZ),
                    monthly.intervalStart());
            assertEquals(ZonedDateTime.of(2026, 9, 1, 0, 0, 0, 0, DELIVERY_TZ),
                    monthly.intervalEnd());

            // MW_CAPACITY: energy = 15 MW × hours in Aug (31 × 24 = 744h)
            BigDecimal expectedEnergy = VOLUME_MW.multiply(new BigDecimal("744"))
                    .setScale(6, RoundingMode.HALF_UP);
            assertEquals(0, expectedEnergy.compareTo(monthly.energy()),
                    "Monthly energy should be volume × hours in month");
        }

        @Test
        @DisplayName("disaggregateMonthlyToDaily should move monthly → daily intervals")
        void shouldDisaggregateMonthlyToDaily() {
            CascadeResult cascade = volumeSeriesService.buildCascadeSeries(
                    "TRADE-1", "LEG-1",
                    deliveryStart, deliveryEnd,
                    TimeGranularity.MIN_15, VOLUME_MW, ProfileType.BASELOAD,
                    VolumeUnit.MW_CAPACITY, DELIVERY_TZ, weekEnd);

            // First materialize Aug in long-term
            VolumeSeries longWithAug = volumeSeriesService.materializeLongTermChunk(
                    cascade.longTerm(), YearMonth.of(2026, 8), VOLUME_MW);

            BigDecimal monthlyEnergy = longWithAug.intervals().get(0).energy();

            // Build an expanded medium-term series that covers Aug
            // (simulates the rolling window having moved forward)
            ZonedDateTime expandedMedStart = ZonedDateTime.of(2026, 7, 1, 0, 0, 0, 0, DELIVERY_TZ);
            ZonedDateTime expandedMedEnd = ZonedDateTime.of(2026, 10, 1, 0, 0, 0, 0, DELIVERY_TZ);
            VolumeSeries expandedMedium = new VolumeSeries(
                    UUID.randomUUID(), "TRADE-1", "LEG-1", 1,
                    VolumeUnit.MW_CAPACITY, expandedMedStart, expandedMedEnd, DELIVERY_TZ,
                    TimeGranularity.DAILY, ProfileType.BASELOAD,
                    MaterializationStatus.PENDING, null,
                    VolumeSeriesService.calculateExpectedIntervals(
                            expandedMedStart, expandedMedEnd, TimeGranularity.DAILY),
                    0, Instant.now(), Instant.now(), new java.util.ArrayList<>(), null,
                    CascadeTier.MEDIUM_TERM);

            // Disaggregate Aug from long-term → expanded medium-term
            DisaggregationResult result = volumeSeriesService.disaggregateMonthlyToDaily(
                    longWithAug, expandedMedium,
                    YearMonth.of(2026, 8), VOLUME_MW);

            // Monthly interval removed from source
            assertEquals(0, result.source().intervals().size(),
                    "Long-term should have no intervals after disaggregation");

            // Daily intervals in target
            assertEquals(31, result.target().intervals().size(),
                    "August should produce 31 daily intervals");

            // Verify contiguity
            List<VolumeInterval> dailyIntervals = result.target().intervals();
            for (int i = 0; i < dailyIntervals.size() - 1; i++) {
                assertEquals(dailyIntervals.get(i).intervalEnd(),
                        dailyIntervals.get(i + 1).intervalStart(),
                        "Gap at daily interval " + i);
            }

            // Energy invariant: sum of daily energies = monthly energy
            BigDecimal dailyTotalEnergy = dailyIntervals.stream()
                    .map(VolumeInterval::energy)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .setScale(6, RoundingMode.HALF_UP);
            assertEquals(0, monthlyEnergy.compareTo(dailyTotalEnergy),
                    "Sum of daily energies must equal monthly energy");
        }

        @Test
        @DisplayName("disaggregateDailyToBase should move daily → 15-min intervals")
        void shouldDisaggregateDailyToBase() {
            CascadeResult cascade = volumeSeriesService.buildCascadeSeries(
                    "TRADE-1", "LEG-1",
                    deliveryStart, deliveryEnd,
                    TimeGranularity.MIN_15, VOLUME_MW, ProfileType.BASELOAD,
                    VolumeUnit.MW_CAPACITY, DELIVERY_TZ, weekEnd);

            // Materialize May in medium-term (31 days of daily intervals)
            VolumeSeries mediumWithMay = volumeSeriesService.materializeMediumTermChunk(
                    cascade.mediumTerm(), YearMonth.of(2026, 5), VOLUME_MW);

            // Disaggregate May 1-3 (3 days) from medium-term → near-term
            LocalDate from = LocalDate.of(2026, 5, 1);
            LocalDate to = LocalDate.of(2026, 5, 4); // exclusive

            // Energy of the 3 daily intervals being disaggregated
            BigDecimal dailyEnergy = mediumWithMay.intervals().stream()
                    .filter(vi -> !vi.intervalStart().toLocalDate().isBefore(from)
                            && vi.intervalStart().toLocalDate().isBefore(to))
                    .map(VolumeInterval::energy)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .setScale(6, RoundingMode.HALF_UP);

            DisaggregationResult result = volumeSeriesService.disaggregateDailyToBase(
                    mediumWithMay, cascade.nearTerm(), from, to, VOLUME_MW);

            // 3 daily intervals removed from medium-term
            assertEquals(31 - 3, result.source().intervals().size(),
                    "Medium-term should have 28 daily intervals remaining");

            // 3 days × 96 = 288 base intervals added to near-term
            int nearOriginal = cascade.nearTerm().intervals().size(); // 288
            assertEquals(nearOriginal + 3 * 96, result.target().intervals().size(),
                    "Near-term should have original + 288 new intervals");

            // Energy invariant: sum of base energies = sum of daily energies
            BigDecimal baseEnergy = result.target().intervals().stream()
                    .skip(nearOriginal) // only the new intervals
                    .map(VolumeInterval::energy)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .setScale(6, RoundingMode.HALF_UP);
            assertEquals(0, dailyEnergy.compareTo(baseEnergy),
                    "Sum of base energies must equal sum of daily energies");
        }

        @Test
        @DisplayName("Daily intervals on DST days should have correct energy")
        void shouldHandleDstInDailyIntervals() {
            // Fall-back: Oct 25 2026 is 25 hours in Europe/Berlin
            // Spring-forward: Mar 28 2027 is 23 hours in Europe/Berlin
            ZonedDateTime dstStart = ZonedDateTime.of(2026, 10, 24, 0, 0, 0, 0, DELIVERY_TZ);
            ZonedDateTime dstEnd = ZonedDateTime.of(2026, 10, 27, 0, 0, 0, 0, DELIVERY_TZ);

            VolumeSeries dstSeries = volumeSeriesService.buildSeries(
                    "TRADE-DST", "LEG-DST",
                    dstStart, dstEnd, TimeGranularity.DAILY,
                    VOLUME_MW, ProfileType.BASELOAD, MaterializationStatus.FULL,
                    VolumeUnit.MW_CAPACITY, DELIVERY_TZ);

            assertEquals(3, dstSeries.intervals().size(), "3 daily intervals");

            VolumeInterval oct24 = dstSeries.intervals().get(0); // normal 24h
            VolumeInterval oct25 = dstSeries.intervals().get(1); // fall-back 25h
            VolumeInterval oct26 = dstSeries.intervals().get(2); // normal 24h

            // 15 MW × 24h = 360 MWh
            BigDecimal energy24h = VOLUME_MW.multiply(new BigDecimal("24"))
                    .setScale(6, RoundingMode.HALF_UP);
            // 15 MW × 25h = 375 MWh
            BigDecimal energy25h = VOLUME_MW.multiply(new BigDecimal("25"))
                    .setScale(6, RoundingMode.HALF_UP);

            assertEquals(0, energy24h.compareTo(oct24.energy()),
                    "Oct 24 (normal day) energy should be 360 MWh");
            assertEquals(0, energy25h.compareTo(oct25.energy()),
                    "Oct 25 (fall-back, 25h) energy should be 375 MWh");
            assertEquals(0, energy24h.compareTo(oct26.energy()),
                    "Oct 26 (normal day) energy should be 360 MWh");

            // Spring-forward test
            ZonedDateTime springStart = ZonedDateTime.of(2027, 3, 27, 0, 0, 0, 0, DELIVERY_TZ);
            ZonedDateTime springEnd = ZonedDateTime.of(2027, 3, 30, 0, 0, 0, 0, DELIVERY_TZ);

            VolumeSeries springSeries = volumeSeriesService.buildSeries(
                    "TRADE-DST2", "LEG-DST2",
                    springStart, springEnd, TimeGranularity.DAILY,
                    VOLUME_MW, ProfileType.BASELOAD, MaterializationStatus.FULL,
                    VolumeUnit.MW_CAPACITY, DELIVERY_TZ);

            VolumeInterval mar28 = springSeries.intervals().get(1); // spring-forward 23h
            BigDecimal energy23h = VOLUME_MW.multiply(new BigDecimal("23"))
                    .setScale(6, RoundingMode.HALF_UP);
            assertEquals(0, energy23h.compareTo(mar28.energy()),
                    "Mar 28 (spring-forward, 23h) energy should be 345 MWh");
        }

        @Test
        @DisplayName("Medium-term should promote to FULL when all chunks materialized")
        void shouldPromoteToFullWhenAllChunksMaterialized() {
            // Short cascade: 1 week near-term, 2 months medium-term, delivery ends Jul 1
            ZonedDateTime shortStart = ZonedDateTime.of(2026, 4, 24, 0, 0, 0, 0, DELIVERY_TZ);
            ZonedDateTime shortEnd = ZonedDateTime.of(2026, 7, 1, 0, 0, 0, 0, DELIVERY_TZ);

            CascadeResult cascade = volumeSeriesService.buildCascadeSeries(
                    "TRADE-SHORT", "LEG-SHORT",
                    shortStart, shortEnd,
                    TimeGranularity.MIN_15, VOLUME_MW, ProfileType.BASELOAD,
                    VolumeUnit.MW_CAPACITY, DELIVERY_TZ, weekEnd);

            VolumeSeries medium = cascade.mediumTerm();
            assertEquals(MaterializationStatus.PENDING, medium.materializationStatus());

            // Materialize rest of Apr (27-30 = 3 days, but clamped to medium window)
            medium = volumeSeriesService.materializeMediumTermChunk(
                    medium, YearMonth.of(2026, 4), VOLUME_MW);
            assertEquals(MaterializationStatus.PARTIAL, medium.materializationStatus());

            // Materialize May (31 days)
            medium = volumeSeriesService.materializeMediumTermChunk(
                    medium, YearMonth.of(2026, 5), VOLUME_MW);
            assertEquals(MaterializationStatus.PARTIAL, medium.materializationStatus());

            // Materialize June (30 days) — should complete
            medium = volumeSeriesService.materializeMediumTermChunk(
                    medium, YearMonth.of(2026, 6), VOLUME_MW);
            assertEquals(MaterializationStatus.FULL, medium.materializationStatus(),
                    "Should be FULL after all medium-term months materialized");
            assertNull(medium.materializedThrough(),
                    "materializedThrough should be null when FULL");
            assertEquals(medium.totalExpectedIntervals(), medium.materializedIntervalCount());
        }
    }
}
