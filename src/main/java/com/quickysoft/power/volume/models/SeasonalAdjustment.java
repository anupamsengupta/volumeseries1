package com.quickysoft.power.volume.models;

import java.math.BigDecimal;
import java.time.Month;

/**
 * Year-over-year or seasonal volume adjustment for long-tenor PPAs.
 * <p>
 * Examples:
 * <ul>
 *   <li>"Year 2 onwards: base volume increases 2% annually" -> multiplier=1.02</li>
 *   <li>"Summer months (Jun-Aug): multiply by 0.7" -> fromMonth=JUNE, toMonth=AUGUST, multiplier=0.7</li>
 *   <li>"Winter premium: add 5 MW flat" -> absoluteAdj=5</li>
 * </ul>
 * <p>
 * When both multiplier and absoluteAdj are set, multiplier is applied first:
 * adjustedVolume = (baseVolume x multiplier) + absoluteAdj
 */
public record SeasonalAdjustment(
        Month fromMonth,
        Month toMonth,
        Integer fromYear,           // null = all years
        Integer toYear,
        BigDecimal multiplier,      // e.g., 1.02 = +2%
        BigDecimal absoluteAdj      // flat MW adjustment
) {}
