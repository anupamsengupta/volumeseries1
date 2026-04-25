package com.quickysoft.power.volume.models;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
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
 */
public class VolumeSeries {

    private UUID id;
    private UUID tradeId;
    private UUID tradeLegId;
    private int tradeVersion;

    // ── Volume interpretation ──
    private VolumeUnit volumeUnit;  // NEW: how to interpret volume on intervals

    // ── Delivery window ──
    private ZonedDateTime deliveryStart;
    private ZonedDateTime deliveryEnd;
    private ZoneId deliveryTimezone;       // e.g., Europe/Berlin
    private TimeGranularity granularity;

    // ── Profile metadata ──
    private ProfileType profileType;

    // ── Two-tier materialization tracking ──
    private MaterializationStatus materializationStatus;
    private YearMonth materializedThrough;  // null if FULL or PENDING
    private int totalExpectedIntervals;
    private int materializedIntervalCount;

    // ── Bi-temporal ──
    private Instant transactionTime;       // when system recorded this
    private Instant validTime;             // when economically effective

    // ── Materialized intervals (ordered by intervalStart) ──
    private List<VolumeInterval> intervals;

    // ── The recipe (for unmaterialized far-dated portion) ──
    private VolumeFormula formula;

    /**
     * Calculate expected interval count for the full delivery period.
     * <p>
     * Walks the timeline using ZonedDateTime arithmetic, which correctly
     * handles DST transitions (23-hour and 25-hour days).
     */
    public int calculateExpectedIntervals() {
        if (granularity == TimeGranularity.MONTHLY) {
            return (int) ChronoUnit.MONTHS.between(
                    YearMonth.from(deliveryStart),
                    YearMonth.from(deliveryEnd));
        }
        int count = 0;
        ZonedDateTime cursor = deliveryStart;
        while (cursor.isBefore(deliveryEnd)) {
            count++;
            cursor = cursor.plus(granularity.getFixedDuration());
        }
        return count;
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

    // ── Getters and Setters ──

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getTradeId() {
        return tradeId;
    }

    public void setTradeId(UUID tradeId) {
        this.tradeId = tradeId;
    }

    public UUID getTradeLegId() {
        return tradeLegId;
    }

    public void setTradeLegId(UUID tradeLegId) {
        this.tradeLegId = tradeLegId;
    }

    public int getTradeVersion() {
        return tradeVersion;
    }

    public void setTradeVersion(int tradeVersion) {
        this.tradeVersion = tradeVersion;
    }

    public ZonedDateTime getDeliveryStart() {
        return deliveryStart;
    }

    public void setDeliveryStart(ZonedDateTime deliveryStart) {
        this.deliveryStart = deliveryStart;
    }

    public ZonedDateTime getDeliveryEnd() {
        return deliveryEnd;
    }

    public void setDeliveryEnd(ZonedDateTime deliveryEnd) {
        this.deliveryEnd = deliveryEnd;
    }

    public ZoneId getDeliveryTimezone() {
        return deliveryTimezone;
    }

    public void setDeliveryTimezone(ZoneId deliveryTimezone) {
        this.deliveryTimezone = deliveryTimezone;
    }

    public TimeGranularity getGranularity() {
        return granularity;
    }

    public void setGranularity(TimeGranularity granularity) {
        this.granularity = granularity;
    }

    public ProfileType getProfileType() {
        return profileType;
    }

    public void setProfileType(ProfileType profileType) {
        this.profileType = profileType;
    }

    public MaterializationStatus getMaterializationStatus() {
        return materializationStatus;
    }

    public void setMaterializationStatus(MaterializationStatus materializationStatus) {
        this.materializationStatus = materializationStatus;
    }

    public YearMonth getMaterializedThrough() {
        return materializedThrough;
    }

    public void setMaterializedThrough(YearMonth materializedThrough) {
        this.materializedThrough = materializedThrough;
    }

    public int getTotalExpectedIntervals() {
        return totalExpectedIntervals;
    }

    public void setTotalExpectedIntervals(int totalExpectedIntervals) {
        this.totalExpectedIntervals = totalExpectedIntervals;
    }

    public int getMaterializedIntervalCount() {
        return materializedIntervalCount;
    }

    public void setMaterializedIntervalCount(int materializedIntervalCount) {
        this.materializedIntervalCount = materializedIntervalCount;
    }

    public Instant getTransactionTime() {
        return transactionTime;
    }

    public void setTransactionTime(Instant transactionTime) {
        this.transactionTime = transactionTime;
    }

    public Instant getValidTime() {
        return validTime;
    }

    public void setValidTime(Instant validTime) {
        this.validTime = validTime;
    }

    public List<VolumeInterval> getIntervals() {
        return intervals;
    }

    public void setIntervals(List<VolumeInterval> intervals) {
        this.intervals = intervals;
    }

    public VolumeFormula getFormula() {
        return formula;
    }

    public void setFormula(VolumeFormula formula) {
        this.formula = formula;
    }
}
