package com.decorix.finance.core.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class NotificationScheduleTest {
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    @Test
    void dailyScheduleMovesAcrossSpringDstGapUsingNextValidLocalTime() {
        Instant next = NotificationSchedule.nextDaily(
                Instant.parse("2026-03-08T06:00:00Z"), LocalTime.of(2, 30), NEW_YORK);

        assertEquals(Instant.parse("2026-03-08T07:30:00Z"), next);
    }

    @Test
    void dailyScheduleUsesEarlierOffsetForFallDstOverlap() {
        Instant next = NotificationSchedule.nextDaily(
                Instant.parse("2026-11-01T04:00:00Z"), LocalTime.of(1, 30), NEW_YORK);

        assertEquals(Instant.parse("2026-11-01T05:30:00Z"), next);
    }

    @Test
    void quietHoursThatCrossMidnightDeferUntilTheLocalEnd() {
        Instant scheduled = Instant.parse("2026-10-06T03:00:00Z");

        Instant available = NotificationSchedule.availableAfterQuietHours(scheduled, NEW_YORK,
                LocalTime.of(22, 0), LocalTime.of(7, 0));

        assertEquals(Instant.parse("2026-10-06T11:00:00Z"), available);
    }

    @Test
    void dailyScheduleCoalescesDowntimeToTheLatestDueLocalDate() {
        Instant latest = NotificationSchedule.latestDailyOccurrence(
                Instant.parse("2026-03-10T15:00:00Z"), LocalTime.of(21, 0), NEW_YORK);

        assertEquals(Instant.parse("2026-03-10T01:00:00Z"), latest);
    }
}
