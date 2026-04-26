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
            ZonedDateTime matEnd = matThrough.plusMonths(1)
                    .atDay(1).atStartOfDay(DELIVERY_TZ);

            // Materialize only the partial window
            List<VolumeInterval> partialIntervals = volumeSeriesService.materializeIntervals(
                    start, matEnd, TimeGranularity.MIN_15,
                    VOLUME_MW, VolumeUnit.MWH_PER_PERIOD);

            // Calculate total expected for the full delivery window
            int totalExpected = volumeSeriesService.buildSeries(
                            UUID.randomUUID().toString(),
                            UUID.randomUUID().toString(),
                    start, end, TimeGranularity.MIN_15,
                    VOLUME_MW, ProfileType.BASELOAD, MaterializationStatus.FULL,
                    VolumeUnit.MWH_PER_PERIOD, DELIVERY_TZ)
                    .calculateExpectedIntervals();

            // Construct partial series with full delivery window but limited intervals
            VolumeSeries series = new VolumeSeries(
                    UUID.randomUUID(), UUID.randomUUID().toString(), UUID.randomUUID().toString(), 1,
                    VolumeUnit.MWH_PER_PERIOD, start, end, DELIVERY_TZ,
                    TimeGranularity.MIN_15, ProfileType.BASELOAD,
                    MaterializationStatus.PARTIAL, matThrough,
                    totalExpected, partialIntervals.size(),
                    Instant.now(), Instant.now(), partialIntervals, null);

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
}
