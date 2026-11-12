package dev.anchorlight.blockvault.util;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The wall-clock slot maths behind the daily and weekly schedules. */
class ScheduleSlotsTest {

    private static final ZoneId SYD = ZoneId.of("Australia/Sydney");

    private static ZonedDateTime at(int month, int day, int hour, int minute) {
        return ZonedDateTime.of(2026, month, day, hour, minute, 0, 0, SYD);
    }

    @Test
    void weeklySlotIsTheMostRecentFridayAtTheHour() {
        // Thursday: the slot is last Friday.
        assertEquals(at(11, 6, 9, 0), ScheduleUtil.latestWeekly(at(11, 12, 10, 0), DayOfWeek.FRIDAY, 9));
        // Friday just before 9: still last Friday.
        assertEquals(at(11, 6, 9, 0), ScheduleUtil.latestWeekly(at(11, 13, 8, 59), DayOfWeek.FRIDAY, 9));
        // Friday at 9 exactly: this Friday.
        assertEquals(at(11, 13, 9, 0), ScheduleUtil.latestWeekly(at(11, 13, 9, 0), DayOfWeek.FRIDAY, 9));
        // Sunday night: this Friday.
        assertEquals(at(11, 13, 9, 0), ScheduleUtil.latestWeekly(at(11, 15, 23, 0), DayOfWeek.FRIDAY, 9));
    }

    @Test
    void dailySlotRollsOverAtTheHour() {
        assertEquals(at(11, 11, 19, 0), ScheduleUtil.latestDaily(at(11, 12, 18, 59), 19));
        assertEquals(at(11, 12, 19, 0), ScheduleUtil.latestDaily(at(11, 12, 19, 0), 19));
    }

    @Test
    void slotKeepsItsLocalHourAcrossDaylightSaving() {
        // Sydney DST starts Sun 4 Oct 2026; the Friday after is still 9am local.
        assertEquals(at(10, 9, 9, 0), ScheduleUtil.latestWeekly(at(10, 10, 12, 0), DayOfWeek.FRIDAY, 9));
        assertEquals(at(10, 2, 9, 0), ScheduleUtil.latestWeekly(at(10, 3, 12, 0), DayOfWeek.FRIDAY, 9));
    }
}
