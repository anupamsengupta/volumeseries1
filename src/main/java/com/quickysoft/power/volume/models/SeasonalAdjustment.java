package com.quickysoft.power.volume.models;

import java.math.BigDecimal;
import java.time.Month;

/**
 * Year-over-year or seasonal volume adjustment for long-tenor PPAs.
 * <p>
 * Examples:
 * <ul>
 *   <li>"Year 2 onwards: base volume increases 2% annually" → multiplier=1.02</li>
 *   <li>"Summer months (Jun-Aug): multiply by 0.7" → fromMonth=JUNE, toMonth=AUGUST, multiplier=0.7</li>
 *   <li>"Winter premium: add 5 MW flat" → absoluteAdj=5</li>
 * </ul>
 * <p>
 * When both multiplier and absoluteAdj are set, multiplier is applied first:
 * adjustedVolume = (baseVolume × multiplier) + absoluteAdj
 */
public class SeasonalAdjustment {

    private Month fromMonth;
    private Month toMonth;
    private Integer fromYear;           // null = all years
    private Integer toYear;
    private BigDecimal multiplier;      // e.g., 1.02 = +2%
    private BigDecimal absoluteAdj;     // flat MW adjustment

    // ── Getters and Setters ──

    public Month getFromMonth() {
        return fromMonth;
    }

    public void setFromMonth(Month fromMonth) {
        this.fromMonth = fromMonth;
    }

    public Month getToMonth() {
        return toMonth;
    }

    public void setToMonth(Month toMonth) {
        this.toMonth = toMonth;
    }

    public Integer getFromYear() {
        return fromYear;
    }

    public void setFromYear(Integer fromYear) {
        this.fromYear = fromYear;
    }

    public Integer getToYear() {
        return toYear;
    }

    public void setToYear(Integer toYear) {
        this.toYear = toYear;
    }

    public BigDecimal getMultiplier() {
        return multiplier;
    }

    public void setMultiplier(BigDecimal multiplier) {
        this.multiplier = multiplier;
    }

    public BigDecimal getAbsoluteAdj() {
        return absoluteAdj;
    }

    public void setAbsoluteAdj(BigDecimal absoluteAdj) {
        this.absoluteAdj = absoluteAdj;
    }
}
