package com.quickysoft.power.volume.models;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.Month;
import java.util.Set;

/**
 * Defines volume for a specific time-of-use block within a shaped profile.
 * <p>
 * Example: "Mon-Fri 08:00-20:00 = 75MW, all other times = 30MW"
 * <p>
 * Uses {@link DayOfWeek} + time ranges instead of EPEX-specific block names,
 * so it works across markets (EPEX, Nord Pool, bilateral OTC with custom blocks).
 */
public class ShapingEntry {

    private Set<DayOfWeek> applicableDays;
    private LocalTime blockStart;       // inclusive
    private LocalTime blockEnd;         // exclusive
    private BigDecimal volume;          // MW
    private boolean appliesToHolidays;

    // ── Optional: month range for seasonal shaping ──
    private Month validFromMonth;       // null = all months
    private Month validToMonth;

    // ── Getters and Setters ──

    public Set<DayOfWeek> getApplicableDays() {
        return applicableDays;
    }

    public void setApplicableDays(Set<DayOfWeek> applicableDays) {
        this.applicableDays = applicableDays;
    }

    public LocalTime getBlockStart() {
        return blockStart;
    }

    public void setBlockStart(LocalTime blockStart) {
        this.blockStart = blockStart;
    }

    public LocalTime getBlockEnd() {
        return blockEnd;
    }

    public void setBlockEnd(LocalTime blockEnd) {
        this.blockEnd = blockEnd;
    }

    public BigDecimal getVolume() {
        return volume;
    }

    public void setVolume(BigDecimal volume) {
        this.volume = volume;
    }

    public boolean isAppliesToHolidays() {
        return appliesToHolidays;
    }

    public void setAppliesToHolidays(boolean appliesToHolidays) {
        this.appliesToHolidays = appliesToHolidays;
    }

    public Month getValidFromMonth() {
        return validFromMonth;
    }

    public void setValidFromMonth(Month validFromMonth) {
        this.validFromMonth = validFromMonth;
    }

    public Month getValidToMonth() {
        return validToMonth;
    }

    public void setValidToMonth(Month validToMonth) {
        this.validToMonth = validToMonth;
    }
}
