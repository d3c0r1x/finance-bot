package com.decorix.finance.core.api;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;

final class NotificationSchedule {
    private NotificationSchedule() {}

    static Instant nextDaily(Instant now, LocalTime time, ZoneId zone) {
        ZonedDateTime localNow = now.atZone(zone);
        ZonedDateTime candidate = localNow.toLocalDate().atTime(time).atZone(zone);
        if (!candidate.isAfter(localNow)) {
            candidate = localNow.toLocalDate().plusDays(1).atTime(time).atZone(zone);
        }
        return candidate.toInstant();
    }

    static Instant latestDailyOccurrence(Instant now, LocalTime time, ZoneId zone) {
        ZonedDateTime localNow = now.atZone(zone);
        ZonedDateTime candidate = localNow.toLocalDate().atTime(time).atZone(zone);
        if (candidate.isAfter(localNow)) {
            candidate = localNow.toLocalDate().minusDays(1).atTime(time).atZone(zone);
        }
        return candidate.toInstant();
    }

    static Instant nextWeekly(Instant now, int isoDayOfWeek, LocalTime time, ZoneId zone) {
        ZonedDateTime localNow = now.atZone(zone);
        DayOfWeek day = DayOfWeek.of(isoDayOfWeek);
        LocalDate date = localNow.toLocalDate().with(TemporalAdjusters.nextOrSame(day));
        ZonedDateTime candidate = date.atTime(time).atZone(zone);
        if (!candidate.isAfter(localNow)) candidate = candidate.plusWeeks(1);
        return candidate.toInstant();
    }

    static Instant latestWeeklyOccurrence(Instant now, int isoDayOfWeek, LocalTime time, ZoneId zone) {
        ZonedDateTime localNow = now.atZone(zone);
        DayOfWeek day = DayOfWeek.of(isoDayOfWeek);
        LocalDate date = localNow.toLocalDate().with(TemporalAdjusters.nextOrSame(day));
        ZonedDateTime candidate = date.atTime(time).atZone(zone);
        if (candidate.isAfter(localNow)) candidate = date.minusWeeks(1).atTime(time).atZone(zone);
        return candidate.toInstant();
    }

    static Instant availableAfterQuietHours(Instant scheduled, ZoneId zone, LocalTime quietStart, LocalTime quietEnd) {
        if (quietStart == null || quietEnd == null) return scheduled;
        LocalDate date = scheduled.atZone(zone).toLocalDate();
        LocalTime time = scheduled.atZone(zone).toLocalTime();
        boolean overnight = quietStart.isAfter(quietEnd);
        boolean inside = overnight ? !time.isBefore(quietStart) || time.isBefore(quietEnd)
                : !time.isBefore(quietStart) && time.isBefore(quietEnd);
        if (!inside) return scheduled;
        LocalDate endDate = overnight && !time.isBefore(quietStart) ? date.plusDays(1) : date;
        return endDate.atTime(quietEnd).atZone(zone).toInstant();
    }
}
