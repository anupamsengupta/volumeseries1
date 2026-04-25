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
public record ShapingEntry(
        Set<DayOfWeek> applicableDays,
        LocalTime blockStart,       // inclusive
        LocalTime blockEnd,         // exclusive
        BigDecimal volume,          // MW
        boolean appliesToHolidays,
        Month validFromMonth,       // null = all months
        Month validToMonth
) {}
