package com.quickysoft.power.volume.models.enums;

import java.time.DayOfWeek;
import java.time.Duration;

public enum WeekendOn {
    SATURDAY(DayOfWeek.SUNDAY),
    SUNDAY(DayOfWeek.MONDAY);

    private final DayOfWeek nextWeekDay;

    WeekendOn(DayOfWeek nextWeekDay) {
        this.nextWeekDay = nextWeekDay;
    }

    public DayOfWeek getNextWeekDay() {
        return nextWeekDay;
    }
}
